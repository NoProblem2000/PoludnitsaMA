package com.poludnitsa.inspector.threads;

import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.StackFrameDto;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.profiler.SubsystemClassifier;
import java.util.List;

/** Stack frame helpers shared by the parsers and analyzers. */
public final class Frames {

    /** Frames of the JDK lock machinery: a parked thread waits "in" the first frame below them. */
    private static final List<String> LOCK_MACHINERY = List.of("jdk.internal.misc.Unsafe", "sun.misc.Unsafe",
            "java.util.concurrent.locks.", "java.lang.Object.wait");

    /**
     * RUNNABLE frames of threads that only wait for work: selectors, accept loops, the reference handler. A thread
     * here in every dump is idle, not stuck.
     */
    private static final List<String> IDLE_TOP = List.of(
            "sun.nio.ch.EPoll.wait", "sun.nio.ch.EPollArrayWrapper.epollWait", "sun.nio.ch.KQueue.poll",
            "sun.nio.ch.KQueueArrayWrapper.kevent0", "sun.nio.ch.WEPoll.wait", "sun.nio.ch.Net.poll",
            "sun.nio.ch.Net.accept", "sun.nio.ch.ServerSocketChannelImpl.accept0", "sun.nio.ch.Net.connect0",
            "sun.nio.ch.WindowsSelectorImpl$SubSelector.poll0", "java.net.PlainSocketImpl.socketAccept",
            "java.net.DualStackPlainSocketImpl.accept0", "java.net.PlainSocketImpl.accept0",
            "java.lang.ref.Reference.waitForReferencePendingList",
            "java.lang.ProcessHandleImpl.waitForProcessExit0", "java.lang.ProcessImpl.waitForInterruptibly",
            "sun.misc.Signal", "jdk.internal.misc.Signal.",
            "sun.nio.fs.LinuxWatchService.poll", "sun.nio.fs.WindowsNativeDispatcher.GetQueuedCompletionStatus",
            "sun.nio.ch.Iocp.getQueuedCompletionStatus");

    /** Native reads where a thread waits for a peer (a database, a remote service). */
    private static final List<String> READ_TOP = List.of("java.net.SocketInputStream.socketRead0",
            "sun.nio.ch.SocketDispatcher.read0", "sun.nio.ch.NioSocketImpl.read", "sun.nio.ch.NioSocketImpl.park",
            "sun.nio.ch.Net.poll", "java.io.FileInputStream.readBytes", "sun.nio.ch.FileDispatcherImpl.read0",
            "sun.nio.ch.SocketChannelImpl.read");

    /** JVM and inspector housekeeping threads, never application work. */
    private static final List<String> SYSTEM_THREADS = List.of("Reference Handler", "Finalizer", "Signal Dispatcher",
            "Attach Listener", "Common-Cleaner", "Notification Thread", "Service Thread", "Monitor Deflation Thread",
            "C1 CompilerThread", "C2 CompilerThread", "Sweeper thread", "JFR ", "RMI ", "JMX server connection",
            "process reaper", "DestroyJavaVM", "Java2D Disposer", "Timer-", "RMI Scheduler",
            "jvm-leak-inspector");

    private Frames() {
    }

    public static StackFrameDto frame(String className, String methodName, String fileName, int line,
                                      List<String> locks) {
        return new StackFrameDto(className, methodName, fileName, line, PackageCategorizer.categorize(className),
                locks);
    }

    public static String describe(StackFrameDto frame) {
        if (frame == null) {
            return null;
        }
        String where;
        if (frame.lineNumber() == -2) {
            where = "Native Method";
        } else if (frame.fileName() == null) {
            where = "Unknown Source";
        } else {
            where = frame.lineNumber() >= 0 ? frame.fileName() + ":" + frame.lineNumber() : frame.fileName();
        }
        return frame.className() + "." + frame.methodName() + "(" + where + ")";
    }

    public static String qualified(StackFrameDto frame) {
        return frame.className() + "." + frame.methodName();
    }

    public static String top(List<StackFrameDto> frames) {
        return frames.isEmpty() ? null : describe(frames.get(0));
    }

    /** Where a thread waits: the top frame, below the JDK lock machinery for parked threads. */
    public static StackFrameDto waitingFrame(List<StackFrameDto> frames) {
        for (StackFrameDto frame : frames) {
            String name = qualified(frame);
            if (LOCK_MACHINERY.stream().noneMatch(name::startsWith)) {
                return frame;
            }
        }
        return frames.isEmpty() ? null : frames.get(0);
    }

    public static StackFrameDto firstApplicationFrame(List<StackFrameDto> frames) {
        for (StackFrameDto frame : frames) {
            if (frame.packageCategory() == PackageCategory.APPLICATION) {
                return frame;
            }
        }
        return null;
    }

    /**
     * RUNNABLE but only waiting for work: a selector or accept loop with no application or JDBC frame (a socket read
     * of a JDBC driver also polls, but that is a query in progress).
     */
    public static boolean idleRunnable(List<StackFrameDto> frames) {
        if (frames.isEmpty()) {
            return true;
        }
        String top = qualified(frames.get(0));
        return IDLE_TOP.stream().anyMatch(top::startsWith) && firstApplicationFrame(frames) == null && !jdbc(frames);
    }

    public static boolean jdbc(List<StackFrameDto> frames) {
        for (StackFrameDto frame : frames) {
            if (SubsystemClassifier.isJdbc(frame.className())) {
                return true;
            }
        }
        return false;
    }

    public static boolean readTop(List<StackFrameDto> frames) {
        for (int i = 0; i < Math.min(4, frames.size()); i++) {
            String name = qualified(frames.get(i));
            if (READ_TOP.stream().anyMatch(name::startsWith)) {
                return true;
            }
        }
        return false;
    }

    public static boolean systemThread(String name) {
        return SYSTEM_THREADS.stream().anyMatch(name::startsWith);
    }

    public static boolean serverThread(String name, List<String> poolPrefixes) {
        return poolPrefixes.stream().anyMatch(name::startsWith);
    }
}
