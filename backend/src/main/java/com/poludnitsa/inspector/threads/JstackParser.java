package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.LockKind;
import com.poludnitsa.inspector.api.dto.StackFrameDto;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads thread dumps as written by {@code jstack}, {@code jcmd <pid> Thread.print}, {@code kill -3} (into the
 * server log) and {@link java.lang.management.ThreadInfo#toString()}, one or many per file. Streams the text line by
 * line; only the parsed threads are kept.
 *
 * <p>VM-internal threads (GC workers, compiler threads) have no {@code java.lang.Thread.State} and are skipped, as
 * they are in a live dump. The "Found one Java-level deadlock" section repeats threads and is skipped too: deadlocks
 * are recomputed from the lock graph ({@link DeadlockDetector}).
 */
public final class JstackParser {

    private static final Pattern TIMESTAMP = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2})");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern HEADER = Pattern.compile("^\"(.*?)\"(?=\\s+(?:#|daemon|prio=|os_prio=|Id=|tid=|\\[))(.*)$");
    private static final Pattern THREAD_ID = Pattern.compile("(?:^|\\s)#(\\d+)(?:\\s|$)");
    private static final Pattern INFO_ID = Pattern.compile("\\bId=(\\d+)\\s+([A-Z_]+)(?: on (\\S+))?(?: owned by \"(.*)\" Id=(\\d+))?");
    private static final Pattern CPU = Pattern.compile("\\bcpu=([\\d.]+)ms");
    private static final Pattern STATE = Pattern.compile("^\\s+java\\.lang\\.Thread\\.State: ([A-Z_]+)");
    private static final Pattern FRAME = Pattern.compile("^\\s+at (\\S+?)\\((.*)\\)\\s*$");
    private static final Pattern LOCK = Pattern.compile(
            "^\\s+-\\s+(waiting to lock|locked|waiting on|parking to wait for|eliminated|waiting to re-lock in wait\\(\\))"
                    + "\\s+<(0x[0-9a-fA-F]+|[0-9a-fA-F]+)>\\s+\\(a ([^)]+)\\)");
    private static final Pattern INFO_LOCK = Pattern.compile("^\\s+-\\s+(blocked on|waiting on|locked|parking to wait for)\\s+(\\S+@[0-9a-fA-F]+)");
    private static final Pattern SYNCHRONIZER = Pattern.compile("^\\s+-\\s+<(0x[0-9a-fA-F]+)>\\s+\\(a ([^)]+)\\)");

    private JstackParser() {
    }

    public static List<ThreadDump> parse(Reader reader) throws IOException {
        Parser parser = new Parser();
        BufferedReader lines = reader instanceof BufferedReader buffered ? buffered : new BufferedReader(reader, 1 << 16);
        String line;
        while ((line = lines.readLine()) != null) {
            parser.line(line);
        }
        return parser.finish();
    }

    private static final class Builder {
        final String name;
        long id = -1;
        boolean daemon;
        JvmThreadState state;
        Double cpuMs;
        final List<Object[]> frames = new ArrayList<>();
        String lockName;
        LockKind lockKind;
        String infoOwner;
        long infoOwnerId = -1;
        final List<String> held = new ArrayList<>();
        boolean synchronizers;

        Builder(String name) {
            this.name = name;
        }

        void lockLine(String verb, String identity) {
            switch (verb) {
                case "waiting to lock", "blocked on" -> {
                    lockName = identity;
                    lockKind = LockKind.MONITOR;
                }
                case "parking to wait for" -> lockName = identity;
                case "waiting on", "waiting to re-lock in wait()" -> {
                    if (lockName == null) {
                        lockName = identity;
                    }
                }
                case "locked" -> held.add(identity);
                default -> {
                    // eliminated: a lock removed by escape analysis
                }
            }
            if (!frames.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> locks = (List<String>) frames.get(frames.size() - 1)[4];
                locks.add(verb + " " + identity);
            }
        }

        ThreadEntry build() {
            List<StackFrameDto> stack = new ArrayList<>(frames.size());
            for (Object[] f : frames) {
                @SuppressWarnings("unchecked")
                List<String> locks = (List<String>) f[4];
                stack.add(Frames.frame((String) f[0], (String) f[1], (String) f[2], (Integer) f[3], locks));
            }
            return new ThreadEntry(id, name, daemon, state, stack, lockName, lockKind, infoOwnerId, infoOwner, held,
                    cpuMs, -1, -1, -1, -1);
        }
    }

    private static final class Parser {
        final List<ThreadDump> dumps = new ArrayList<>();
        List<ThreadEntry> threads;
        Instant pendingTime;
        Instant dumpTime;
        String jvmInfo;
        Builder current;
        boolean skipping;

        void line(String line) {
            Matcher time = TIMESTAMP.matcher(line);
            if (time.find() && line.length() < 40) {
                closeDump();
                pendingTime = parseTime(time.group(1));
                skipping = false;
                return;
            }
            if (line.startsWith("Full thread dump")) {
                closeDump();
                startDump(line.endsWith(":") ? line.substring(0, line.length() - 1) : line);
                return;
            }
            if (line.startsWith("Found one Java-level deadlock") || line.startsWith("Found a total of")
                    || line.startsWith("JNI global refs") || line.startsWith("JNI global references")) {
                closeThread();
                skipping = !line.startsWith("JNI global");
                return;
            }
            if (skipping) {
                return;
            }
            Matcher header = HEADER.matcher(line);
            if (header.matches()) {
                closeThread();
                if (threads == null) {
                    startDump(null);
                }
                current = new Builder(header.group(1));
                String rest = header.group(2);
                Matcher id = THREAD_ID.matcher(rest);
                if (id.find()) {
                    current.id = Long.parseLong(id.group(1));
                }
                current.daemon = rest.matches(".*\\bdaemon\\b.*");
                Matcher cpu = CPU.matcher(rest);
                if (cpu.find()) {
                    current.cpuMs = Double.parseDouble(cpu.group(1));
                }
                Matcher info = INFO_ID.matcher(rest);
                if (info.find()) {
                    // ThreadInfo.toString(): "name" daemon prio=5 Id=12 BLOCKED on x@1 owned by "t" Id=11
                    current.id = Long.parseLong(info.group(1));
                    current.state = state(info.group(2));
                    if (info.group(3) != null) {
                        current.lockName = info.group(3);
                        current.lockKind = current.state == JvmThreadState.BLOCKED ? LockKind.MONITOR : null;
                    }
                    if (info.group(4) != null) {
                        current.infoOwner = info.group(4);
                        current.infoOwnerId = Long.parseLong(info.group(5));
                    }
                }
                return;
            }
            if (current == null) {
                return;
            }
            Matcher state = STATE.matcher(line);
            if (state.find()) {
                current.state = state(state.group(1));
                return;
            }
            Matcher frame = FRAME.matcher(line);
            if (frame.matches()) {
                addFrame(frame.group(1), frame.group(2));
                return;
            }
            Matcher lock = LOCK.matcher(line);
            if (lock.find()) {
                String address = lock.group(2).startsWith("0x") ? lock.group(2) : "0x" + lock.group(2);
                String identity = lock.group(3) + "@" + address;
                if (current.synchronizers) {
                    current.held.add(identity);
                } else {
                    current.lockLine(lock.group(1), identity);
                }
                return;
            }
            Matcher info = INFO_LOCK.matcher(line);
            if (info.find()) {
                current.lockLine(info.group(1), info.group(2));
                return;
            }
            if (line.trim().startsWith("Locked ownable synchronizers:")) {
                current.synchronizers = true;
                return;
            }
            if (current.synchronizers) {
                Matcher synchronizer = SYNCHRONIZER.matcher(line);
                if (synchronizer.find()) {
                    current.held.add(synchronizer.group(2) + "@" + synchronizer.group(1));
                }
            }
        }

        void addFrame(String qualified, String source) {
            // "java.base@17.0.2/java.lang.Object.wait", "app//com.acme.Service.run"
            int slash = qualified.lastIndexOf('/');
            String name = slash >= 0 ? qualified.substring(slash + 1) : qualified;
            int dot = name.lastIndexOf('.');
            String className = dot > 0 ? name.substring(0, dot) : name;
            String method = dot > 0 ? name.substring(dot + 1) : "";
            String file = null;
            int line = -1;
            if (source.equals("Native Method")) {
                line = -2;
            } else if (!source.equals("Unknown Source") && !source.isEmpty()) {
                int colon = source.lastIndexOf(':');
                if (colon > 0) {
                    file = source.substring(0, colon);
                    try {
                        line = Integer.parseInt(source.substring(colon + 1));
                    } catch (NumberFormatException e) {
                        line = -1;
                    }
                } else {
                    file = source;
                }
            }
            current.frames.add(new Object[] {className, method, file, line, new ArrayList<String>()});
        }

        void startDump(String info) {
            threads = new ArrayList<>();
            dumpTime = pendingTime;
            pendingTime = null;
            jvmInfo = info;
            skipping = false;
        }

        void closeThread() {
            if (current != null && current.state != null && threads != null) {
                threads.add(current.build());
            }
            current = null;
        }

        void closeDump() {
            closeThread();
            if (threads != null && !threads.isEmpty()) {
                dumps.add(new ThreadDump(dumps.size(), dumpTime, jvmInfo, resolveOwners(threads)));
            }
            threads = null;
            jvmInfo = null;
            dumpTime = null;
        }

        List<ThreadDump> finish() {
            closeDump();
            return dumps;
        }
    }

    /** jstack names the owner of a lock only through the owner's "- locked" lines. */
    static List<ThreadEntry> resolveOwners(List<ThreadEntry> threads) {
        Map<String, ThreadEntry> holders = new HashMap<>();
        for (ThreadEntry thread : threads) {
            for (String held : thread.heldLocks()) {
                holders.put(held, thread);
            }
        }
        List<ThreadEntry> result = new ArrayList<>(threads.size());
        for (ThreadEntry thread : threads) {
            ThreadEntry owner = thread.lockName() == null ? null : holders.get(thread.lockName());
            if (owner != null && owner != thread) {
                LockKind kind = thread.state() == JvmThreadState.BLOCKED ? LockKind.MONITOR
                        : thread.state() == JvmThreadState.WAITING || thread.state() == JvmThreadState.TIMED_WAITING
                        ? isParked(thread) ? LockKind.SYNCHRONIZER : null : null;
                result.add(thread.withOwner(owner.id(), owner.name(), kind));
            } else if (thread.lockOwnerName() != null && thread.lockKind() == null
                    && thread.state() != JvmThreadState.BLOCKED && isParked(thread)) {
                result.add(thread.withOwner(thread.lockOwnerId(), thread.lockOwnerName(), LockKind.SYNCHRONIZER));
            } else {
                result.add(thread);
            }
        }
        return result;
    }

    private static boolean isParked(ThreadEntry thread) {
        for (StackFrameDto frame : thread.frames()) {
            for (String lock : frame.locks()) {
                if (lock.startsWith("parking to wait for")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JvmThreadState state(String text) {
        try {
            return JvmThreadState.valueOf(text);
        } catch (IllegalArgumentException e) {
            return JvmThreadState.RUNNABLE;
        }
    }

    private static Instant parseTime(String text) {
        try {
            return LocalDateTime.parse(text.replace('T', ' '), TIME).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
