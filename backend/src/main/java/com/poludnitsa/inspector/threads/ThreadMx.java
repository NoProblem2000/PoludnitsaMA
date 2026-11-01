package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.LockKind;
import com.poludnitsa.inspector.api.dto.StackFrameDto;
import com.poludnitsa.inspector.jmx.MBeanNames;
import java.io.IOException;
import java.lang.management.LockInfo;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;

/**
 * The target's {@code ThreadMXBean} over its {@link MBeanServerConnection}: raw {@code invoke} / attribute calls, so
 * the {@link ThreadInfo}s come from the target, never from the inspector's own JVM.
 */
public final class ThreadMx {

    private static final String[] DUMP_ALL = {"boolean", "boolean"};
    private static final String[] DUMP_ALL_DEPTH = {"boolean", "boolean", "int"};
    private static final String[] IDS = {"[J"};
    private static final String[] INFO = {"[J", "boolean", "boolean"};

    /** Contention / CPU monitoring flags of a target before the inspector changed them. */
    public record Flags(boolean contentionSupported, boolean contention, boolean cpuSupported, boolean cpu) {
    }

    private ThreadMx() {
    }

    public static Flags flags(MBeanServerConnection connection) throws IOException, JMException {
        AttributeList values = connection.getAttributes(MBeanNames.THREADING, new String[] {
                "ThreadContentionMonitoringSupported", "ThreadContentionMonitoringEnabled",
                "ThreadCpuTimeSupported", "ThreadCpuTimeEnabled"});
        return new Flags(bool(values, "ThreadContentionMonitoringSupported"),
                bool(values, "ThreadContentionMonitoringEnabled"), bool(values, "ThreadCpuTimeSupported"),
                bool(values, "ThreadCpuTimeEnabled"));
    }

    /** {@code setThreadContentionMonitoringEnabled(true)} and {@code setThreadCpuTimeEnabled(true)} where supported. */
    public static Flags enable(MBeanServerConnection connection) throws IOException, JMException {
        Flags before = flags(connection);
        if (before.contentionSupported() && !before.contention()) {
            connection.setAttribute(MBeanNames.THREADING, new Attribute("ThreadContentionMonitoringEnabled", true));
        }
        if (before.cpuSupported() && !before.cpu()) {
            connection.setAttribute(MBeanNames.THREADING, new Attribute("ThreadCpuTimeEnabled", true));
        }
        return before;
    }

    public static void restore(MBeanServerConnection connection, Flags before) throws IOException, JMException {
        if (before.contentionSupported() && !before.contention()) {
            connection.setAttribute(MBeanNames.THREADING, new Attribute("ThreadContentionMonitoringEnabled", false));
        }
        if (before.cpuSupported() && !before.cpu()) {
            connection.setAttribute(MBeanNames.THREADING, new Attribute("ThreadCpuTimeEnabled", false));
        }
    }

    /**
     * {@code dumpAllThreads}: with {@code locks}, the monitors and synchronizers each thread holds (needed for the
     * lock graph); {@code maxDepth} limits the stacks of frequent samples (Java 10+, else full stacks).
     */
    public static List<ThreadEntry> dumpAll(MBeanServerConnection connection, boolean locks, int maxDepth)
            throws IOException, JMException {
        Object result;
        try {
            result = maxDepth > 0
                    ? connection.invoke(MBeanNames.THREADING, "dumpAllThreads", new Object[] {locks, locks, maxDepth},
                    DUMP_ALL_DEPTH)
                    : connection.invoke(MBeanNames.THREADING, "dumpAllThreads", new Object[] {locks, locks}, DUMP_ALL);
        } catch (ReflectionException e) {
            result = connection.invoke(MBeanNames.THREADING, "dumpAllThreads", new Object[] {locks, locks}, DUMP_ALL);
        }
        return convert((CompositeData[]) result);
    }

    /** Threads in a deadlock: {@code findDeadlockedThreads} (monitors and synchronizers), else the monitor-only one. */
    public static long[] deadlocked(MBeanServerConnection connection) throws IOException, JMException {
        Object ids;
        try {
            ids = connection.invoke(MBeanNames.THREADING, "findDeadlockedThreads", null, null);
        } catch (ReflectionException | UnsupportedOperationException e) {
            ids = null;
        }
        if (ids == null) {
            ids = connection.invoke(MBeanNames.THREADING, "findMonitorDeadlockedThreads", null, null);
        }
        return ids instanceof long[] array ? array : new long[0];
    }

    public static List<ThreadEntry> info(MBeanServerConnection connection, long[] ids) throws IOException, JMException {
        Object result = connection.invoke(MBeanNames.THREADING, "getThreadInfo", new Object[] {ids, true, true}, INFO);
        return convert((CompositeData[]) result);
    }

    /** CPU time per thread id in ms ({@code com.sun.management.ThreadMXBean#getThreadCpuTime(long[])}). */
    public static Map<Long, Double> cpuMs(MBeanServerConnection connection, long[] ids) {
        Map<Long, Double> result = new HashMap<>();
        try {
            Object times = connection.invoke(MBeanNames.THREADING, "getThreadCpuTime", new Object[] {ids}, IDS);
            if (times instanceof long[] nanos) {
                for (int i = 0; i < Math.min(ids.length, nanos.length); i++) {
                    if (nanos[i] >= 0) {
                        result.put(ids[i], nanos[i] / 1e6);
                    }
                }
            }
        } catch (IOException | JMException | RuntimeException e) {
            // not a HotSpot ThreadMXBean, or CPU time disabled: no CPU column
        }
        return result;
    }

    public static int intAttribute(MBeanServerConnection connection, String name) throws IOException, JMException {
        Object value = connection.getAttribute(MBeanNames.THREADING, name);
        return value instanceof Number number ? number.intValue() : 0;
    }

    static List<ThreadEntry> convert(CompositeData[] data) {
        List<ThreadEntry> result = new ArrayList<>();
        if (data == null) {
            return result;
        }
        for (CompositeData item : data) {
            if (item != null) {
                result.add(convert(ThreadInfo.from(item)));
            }
        }
        return result;
    }

    static ThreadEntry convert(ThreadInfo info) {
        StackTraceElement[] trace = info.getStackTrace();
        List<List<String>> locks = new ArrayList<>(trace.length);
        for (int i = 0; i < trace.length; i++) {
            locks.add(new ArrayList<>());
        }
        JvmThreadState state = JvmThreadState.valueOf(info.getThreadState().name());
        String lockName = info.getLockName();
        boolean parked = false;
        if (lockName != null && trace.length > 0) {
            parked = state != JvmThreadState.BLOCKED && trace[0].getMethodName().startsWith("park");
            String verb = state == JvmThreadState.BLOCKED ? "waiting to lock" : parked ? "parking to wait for" : "waiting on";
            locks.get(0).add(verb + " " + lockName);
        }
        List<String> held = new ArrayList<>();
        for (MonitorInfo monitor : info.getLockedMonitors()) {
            String identity = monitor.toString();
            held.add(identity);
            int depth = monitor.getLockedStackDepth();
            if (depth >= 0 && depth < locks.size()) {
                locks.get(depth).add("locked " + identity);
            }
        }
        for (LockInfo synchronizer : info.getLockedSynchronizers()) {
            held.add(synchronizer.toString());
        }
        List<StackFrameDto> frames = new ArrayList<>(trace.length);
        for (int i = 0; i < trace.length; i++) {
            StackTraceElement element = trace[i];
            int line = element.isNativeMethod() ? -2 : element.getLineNumber();
            frames.add(Frames.frame(element.getClassName(), element.getMethodName(), element.getFileName(), line,
                    locks.get(i)));
        }
        long owner = info.getLockOwnerId();
        LockKind kind = state == JvmThreadState.BLOCKED ? LockKind.MONITOR
                : parked && owner >= 0 ? LockKind.SYNCHRONIZER : null;
        return new ThreadEntry(info.getThreadId(), info.getThreadName(), info.isDaemon(), state, frames, lockName, kind,
                owner, info.getLockOwnerName(), held, null, info.getBlockedCount(), info.getBlockedTime(),
                info.getWaitedCount(), info.getWaitedTime());
    }

    private static boolean bool(AttributeList values, String name) {
        for (Attribute attribute : values.asList()) {
            if (attribute.getName().equals(name)) {
                return Boolean.TRUE.equals(attribute.getValue());
            }
        }
        return false;
    }
}
