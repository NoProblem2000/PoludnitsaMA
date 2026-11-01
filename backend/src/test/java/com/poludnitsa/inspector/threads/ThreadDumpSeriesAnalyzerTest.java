package com.poludnitsa.inspector.threads;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.DeadlockDto;
import com.poludnitsa.inspector.api.dto.DiagnosticSource;
import com.poludnitsa.inspector.api.dto.JobStatus;
import com.poludnitsa.inspector.api.dto.JvmThreadState;
import com.poludnitsa.inspector.api.dto.LockKind;
import com.poludnitsa.inspector.api.dto.MonitorContentionDto;
import com.poludnitsa.inspector.api.dto.StackClusterDto;
import com.poludnitsa.inspector.api.dto.StuckKind;
import com.poludnitsa.inspector.api.dto.StuckThreadDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesAnalysisDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesRowDto;
import com.poludnitsa.inspector.api.dto.ThreadSeriesSummaryDto;
import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A jstack file with three dumps 2 s apart: a CPU loop, a request blocked on a lock, a deadlock, an idle pool. */
class ThreadDumpSeriesAnalyzerTest {

    private static final ThreadDumpSeriesAnalyzer.Settings SETTINGS = new ThreadDumpSeriesAnalyzer.Settings(3, 64,
            List.of("default task-", "http-nio-"), 100);

    @Test
    void parsesEveryDumpWithThreadsLocksAndOwners() throws IOException {
        List<ThreadDump> dumps = JstackParser.parse(new StringReader(series()));

        assertThat(dumps).hasSize(3);
        assertThat(ThreadDumpSeriesAnalyzer.medianIntervalMs(dumps)).isEqualTo(2_000L);
        ThreadDump first = dumps.get(0);
        assertThat(first.jvmInfo()).contains("OpenJDK");
        assertThat(first.threads()).extracting(ThreadEntry::name).contains("default task-1", "default task-2",
                "default task-3", "Thread-A", "Thread-B", "pool-1-thread-1", "Reference Handler");
        ThreadEntry blocked = thread(first, "default task-2");
        assertThat(blocked.state()).isEqualTo(JvmThreadState.BLOCKED);
        assertThat(blocked.lockKind()).isEqualTo(LockKind.MONITOR);
        assertThat(blocked.lockClass()).isEqualTo("com.acme.Cache");
        assertThat(blocked.lockOwnerName()).isEqualTo("default task-3");
        assertThat(thread(first, "default task-1").cpuMs()).isEqualTo(5_000.0);
        assertThat(thread(first, "default task-3").heldLocks()).hasSize(1);
        assertThat(thread(first, "default task-1").frames().get(0).className()).isEqualTo("com.acme.Report");
    }

    @Test
    void findsStuckThreadsDeadlockClustersAndContention() throws IOException {
        ThreadSeriesAnalysisDto analysis = analyze(series());

        assertThat(analysis.dumps()).hasSize(3);
        assertThat(analysis.dumps().get(2).offsetMs()).isEqualTo(4_000L);

        StuckThreadDto loop = stuck(analysis, "default task-1");
        assertThat(loop.kind()).isEqualTo(StuckKind.CPU_LOOP);
        assertThat(loop.dumps()).isEqualTo(3);
        assertThat(loop.durationMs()).isEqualTo(4_000L);
        assertThat(loop.cpuDeltaMs()).isEqualTo(3_600.0);
        assertThat(loop.serverThread()).isTrue();
        assertThat(loop.stack().get(0).methodName()).isEqualTo("compute");

        assertThat(stuck(analysis, "default task-2").kind()).isEqualTo(StuckKind.LOCK_WAIT);
        assertThat(stuck(analysis, "default task-2").lockName()).contains("com.acme.Cache");
        assertThat(stuck(analysis, "jdbc-1").kind()).isEqualTo(StuckKind.DB_QUERY);
        assertThat(analysis.stuck()).extracting(StuckThreadDto::name)
                .doesNotContain("pool-1-thread-1", "Reference Handler", "default task-3");

        DeadlockDto deadlock = analysis.deadlocks().get(0);
        assertThat(analysis.deadlocks()).hasSize(1);
        assertThat(deadlock.kind()).isEqualTo(LockKind.MONITOR);
        assertThat(deadlock.cycle()).extracting(n -> n.threadName()).containsExactlyInAnyOrder("Thread-A", "Thread-B");
        assertThat(deadlock.cycle()).allSatisfy(n -> assertThat(n.heldByThreadName()).isIn("Thread-A", "Thread-B")
                .isNotEqualTo(n.threadName()));

        StackClusterDto pool = analysis.clusters().stream().filter(c -> c.threads().contains("pool-1-thread-1"))
                .findFirst().orElseThrow();
        assertThat(pool.threadCount()).isEqualTo(3);
        assertThat(pool.state()).isEqualTo(JvmThreadState.WAITING);

        ThreadSeriesRowDto row = analysis.rows().stream().filter(r -> r.name().equals("default task-2")).findFirst()
                .orElseThrow();
        assertThat(row.stuck()).isTrue();
        assertThat(row.cells()).hasSize(3);
        assertThat(row.cells().get(0).sameAsPrevious()).isFalse();
        assertThat(row.cells().get(1).sameAsPrevious()).isTrue();
        assertThat(row.cells().get(2).sameAsPrevious()).isTrue();
        assertThat(analysis.rows().get(0).stuck()).isTrue();
        assertThat(analysis.stacks()).isNotEmpty();

        MonitorContentionDto cache = analysis.contention().stream().filter(c -> c.lockClass().equals("com.acme.Cache"))
                .findFirst().orElseThrow();
        assertThat(cache.timeMeasured()).isFalse();
        assertThat(cache.samples()).isEqualTo(3);
        assertThat(cache.topOwners()).containsExactly("default task-3");
        assertThat(cache.serverThreads()).isEqualTo(1);
        assertThat(cache.blockedAt()).contains("Cache.get");
    }

    @Test
    void threadThatMovesIsNotStuck() throws IOException {
        String moving = dump("2026-11-01 15:00:00", 1, "com.acme.A.one(A.java:1)")
                + dump("2026-11-01 15:00:02", 2, "com.acme.A.two(A.java:2)")
                + dump("2026-11-01 15:00:04", 3, "com.acme.A.three(A.java:3)");
        ThreadSeriesAnalysisDto analysis = analyze(moving);

        assertThat(analysis.stuck()).isEmpty();
        assertThat(analysis.rows().get(0).cells()).noneMatch(c -> c.sameAsPrevious());
    }

    @Test
    void parsesThreadInfoToStringFormat() throws IOException {
        String text = """
                "worker" daemon prio=5 Id=17 BLOCKED on com.acme.Cache@1b2c3d owned by "loader" Id=18
                	at com.acme.Cache.get(Cache.java:20)
                	-  blocked on com.acme.Cache@1b2c3d
                	at com.acme.Service.call(Service.java:5)

                "loader" prio=5 Id=18 RUNNABLE
                	at com.acme.Cache.reload(Cache.java:50)
                	-  locked com.acme.Cache@1b2c3d

                """;
        ThreadDump dump = JstackParser.parse(new StringReader(text)).get(0);

        ThreadEntry worker = thread(dump, "worker");
        assertThat(worker.id()).isEqualTo(17);
        assertThat(worker.daemon()).isTrue();
        assertThat(worker.state()).isEqualTo(JvmThreadState.BLOCKED);
        assertThat(worker.lockName()).isEqualTo("com.acme.Cache@1b2c3d");
        assertThat(worker.lockOwnerId()).isEqualTo(18);
    }

    @Test
    void textWithoutThreadsIsNoDump() throws IOException {
        assertThat(JstackParser.parse(new StringReader("hello\nworld\n"))).isEmpty();
    }

    // ---------------------------------------------------------------- data

    private static ThreadSeriesAnalysisDto analyze(String text) throws IOException {
        List<ThreadDump> dumps = JstackParser.parse(new StringReader(text));
        ThreadSeriesSummaryDto summary = new ThreadSeriesSummaryDto("thr-test", DiagnosticSource.FILE, null,
                "test.txt", JobStatus.READY, Instant.now(), dumps.size(), dumps.size(),
                ThreadDumpSeriesAnalyzer.medianIntervalMs(dumps), null, null, 0, 0, 0, 0);
        return ThreadDumpSeriesAnalyzer.analyze(summary, dumps, null, false, SETTINGS);
    }

    private static String series() {
        return seriesDump("2026-11-01 15:00:00", 5_000) + seriesDump("2026-11-01 15:00:02", 6_800)
                + seriesDump("2026-11-01 15:00:04", 8_600);
    }

    private static String seriesDump(String time, int cpu) {
        StringBuilder text = new StringBuilder();
        text.append(time).append("\nFull thread dump OpenJDK 64-Bit Server VM (21.0.12.1+1-LTS mixed mode, sharing):\n\n");
        text.append("""
                "Reference Handler" #9 [1001] daemon prio=10 os_prio=2 cpu=0.00ms elapsed=100.00s tid=0x01 nid=1001 waiting on condition  [0x0]
                   java.lang.Thread.State: RUNNABLE
                	at java.lang.ref.Reference.waitForReferencePendingList(java.base@21.0.12.1/Native Method)
                	at java.lang.ref.Reference.processPendingReferences(java.base@21.0.12.1/Reference.java:246)

                """);
        text.append("\"default task-1\" #31 [12345] prio=5 os_prio=0 cpu=").append(cpu).append(".00ms elapsed=100.00s ")
                .append("tid=0x0000000000000001 nid=12345 runnable  [0x0000]\n");
        text.append("""
                   java.lang.Thread.State: RUNNABLE
                	at com.acme.Report.compute(Report.java:42)
                	at com.acme.ReportServlet.doGet(ReportServlet.java:10)
                	at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:500)

                "default task-2" #32 [12346] prio=5 os_prio=0 cpu=10.00ms elapsed=100.00s tid=0x02 nid=12346 waiting for monitor entry  [0x0]
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at com.acme.Cache.get(Cache.java:20)
                	- waiting to lock <0x00000000c0000001> (a com.acme.Cache)
                	at com.acme.Service.call(Service.java:5)

                "default task-3" #33 [12347] prio=5 os_prio=0 cpu=10.00ms elapsed=100.00s tid=0x03 nid=12347 runnable  [0x0]
                   java.lang.Thread.State: RUNNABLE
                """);
        // the owner of the cache lock moves: not stuck itself
        text.append("\tat com.acme.Cache.reload(Cache.java:").append(50 + cpu % 7).append(")\n");
        text.append("""
                	- locked <0x00000000c0000001> (a com.acme.Cache)
                	at com.acme.Service.reload(Service.java:9)

                "jdbc-1" #34 [12348] prio=5 os_prio=0 cpu=12.00ms elapsed=100.00s tid=0x04 nid=12348 runnable  [0x0]
                   java.lang.Thread.State: RUNNABLE
                	at java.net.SocketInputStream.socketRead0(java.base@21.0.12.1/Native Method)
                	at org.postgresql.core.VisibleBufferedInputStream.read(VisibleBufferedInputStream.java:161)
                	at org.postgresql.jdbc.PgPreparedStatement.executeQuery(PgPreparedStatement.java:134)
                	at com.acme.OrderDao.find(OrderDao.java:77)

                "Thread-A" #40 [12350] prio=5 os_prio=0 cpu=1.00ms elapsed=100.00s tid=0x05 nid=12350 waiting for monitor entry  [0x0]
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at com.acme.Bank.transfer(Bank.java:10)
                	- waiting to lock <0x00000000d0000002> (a java.lang.Object)
                	- locked <0x00000000d0000001> (a java.lang.Object)
                	at java.lang.Thread.run(java.base@21.0.12.1/Thread.java:1583)

                "Thread-B" #41 [12351] prio=5 os_prio=0 cpu=1.00ms elapsed=100.00s tid=0x06 nid=12351 waiting for monitor entry  [0x0]
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at com.acme.Bank.transfer(Bank.java:10)
                	- waiting to lock <0x00000000d0000001> (a java.lang.Object)
                	- locked <0x00000000d0000002> (a java.lang.Object)
                	at java.lang.Thread.run(java.base@21.0.12.1/Thread.java:1583)

                """);
        for (int i = 1; i <= 3; i++) {
            text.append("\"pool-1-thread-").append(i).append("\" #").append(50 + i).append(" [").append(13000 + i)
                    .append("] prio=5 os_prio=0 cpu=0.50ms elapsed=100.00s tid=0x1").append(i).append(" nid=")
                    .append(13000 + i).append(" waiting on condition  [0x0]\n");
            text.append("""
                       java.lang.Thread.State: WAITING (parking)
                    	at jdk.internal.misc.Unsafe.park(java.base@21.0.12.1/Native Method)
                    	- parking to wait for  <0x00000000e0000001> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
                    	at java.util.concurrent.locks.LockSupport.park(java.base@21.0.12.1/LockSupport.java:371)
                    	at java.util.concurrent.LinkedBlockingQueue.take(java.base@21.0.12.1/LinkedBlockingQueue.java:435)
                    	at java.util.concurrent.ThreadPoolExecutor.getTask(java.base@21.0.12.1/ThreadPoolExecutor.java:1070)

                    """);
        }
        text.append("\"VM Thread\" os_prio=2 cpu=15.63ms elapsed=100.00s tid=0x07 nid=1000 runnable\n\n");
        text.append("JNI global refs: 15, weak refs: 0\n\n");
        return text.toString();
    }

    private static String dump(String time, int index, String frame) {
        return time + "\nFull thread dump OpenJDK 64-Bit Server VM (21.0.12.1+1-LTS mixed mode, sharing):\n\n"
                + "\"default task-9\" #31 [1] prio=5 os_prio=0 cpu=" + index + ".00ms elapsed=1.00s tid=0x1 nid=1"
                + " runnable  [0x0]\n   java.lang.Thread.State: RUNNABLE\n\tat " + frame + "\n\n";
    }

    private static ThreadEntry thread(ThreadDump dump, String name) {
        return dump.threads().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not in dump"));
    }

    private static StuckThreadDto stuck(ThreadSeriesAnalysisDto analysis, String name) {
        return analysis.stuck().stream().filter(s -> s.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " not stuck: " + analysis.stuck()));
    }
}
