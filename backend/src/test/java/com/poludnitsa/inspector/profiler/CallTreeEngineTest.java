package com.poludnitsa.inspector.profiler;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.ButterflyDto;
import com.poludnitsa.inspector.api.dto.DiffFlameNodeDto;
import com.poludnitsa.inspector.api.dto.HotspotDto;
import com.poludnitsa.inspector.api.dto.ProfileMetric;
import com.poludnitsa.inspector.api.dto.Subsystem;
import com.poludnitsa.inspector.api.dto.SubsystemBreakdownDto;
import com.poludnitsa.inspector.api.dto.SubsystemShareDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallTreeEngineTest {

    private static final long MS = 1_000;

    /** Builds a profile from stacks written root first: "a.Main.run > b.Svc.work". */
    private static final class Profile {
        final ProfileBuilder builder = new ProfileBuilder(10_000);
        final int thread = builder.thread(1, "default task-1");

        int stack(String path) {
            String[] frames = path.split(" > ");
            int[] ids = new int[frames.length];
            for (int i = 0; i < frames.length; i++) {
                String frame = frames[frames.length - 1 - i];
                int dot = frame.lastIndexOf('.');
                ids[i] = builder.frame(frame.substring(0, dot), frame.substring(dot + 1));
            }
            return builder.stack(ids);
        }

        Profile cpu(String path, int samples, double atMs) {
            int stack = stack(path);
            for (int i = 0; i < samples; i++) {
                builder.sample(atMs + i, thread, stack, ProfileData.SampleKind.CPU, 10 * MS);
            }
            return this;
        }

        ProfileData build() {
            return builder.build(0, 10_000, 10 * MS);
        }
    }

    private static final String REQUEST = "io.undertow.server.Connectors.executeRootHandler > com.acme.OrderResource.get";

    @Test
    void hotspotsRankSelfTimeAndSubsystems() {
        ProfileData data = new Profile()
                .cpu(REQUEST + " > com.acme.Pricing.compute", 60, 0)
                .cpu(REQUEST + " > org.hibernate.loader.Loader.doQuery > org.postgresql.jdbc.PgStatement.execute", 30, 100)
                .cpu("io.undertow.server.Connectors.executeRootHandler > io.undertow.util.Headers.parse", 10, 200)
                .build();

        List<HotspotDto> hotspots = CallTreeEngine.hotspots(data, ProfileMetric.CPU, 0, 1e9, -1, 10);

        assertThat(hotspots.get(0).methodName()).isEqualTo("compute");
        assertThat(hotspots.get(0).selfPercent()).isEqualTo(60.0);
        assertThat(hotspots.get(0).subsystem()).isEqualTo(Subsystem.BUSINESS);
        assertThat(hotspots.get(1).subsystem()).isEqualTo(Subsystem.JDBC);
        assertThat(hotspots.get(2).subsystem()).isEqualTo(Subsystem.HTTP);

        SubsystemBreakdownDto breakdown = SubsystemBreakdownBuilder.build("p", data, ProfileMetric.CPU, 0, 1e9, -1);
        assertThat(breakdown.subsystems()).extracting(SubsystemShareDto::subsystem)
                .containsExactly(Subsystem.BUSINESS, Subsystem.JDBC, Subsystem.HTTP);
        assertThat(breakdown.totalValue()).isEqualTo(100 * 10 * MS);
    }

    @Test
    void butterflyShowsCallersAndCallees() {
        ProfileData data = new Profile()
                .cpu("app.A.main > app.Order.save > app.Repo.flush > app.Db.exec", 40, 0)
                .cpu("app.B.main > app.Batch.run > app.Repo.flush > app.Db.exec", 20, 100)
                .cpu("app.B.main > app.Batch.run > app.Repo.flush", 10, 200)
                .build();

        ButterflyDto butterfly = CallTreeEngine.butterfly("p", data, ProfileMetric.CPU, "app.Repo", "flush", 0, 1e9,
                -1, 0, 1000);

        assertThat(butterfly.totalValue()).isEqualTo(70 * 10 * MS);
        assertThat(butterfly.selfValue()).isEqualTo(10 * 10 * MS);
        assertThat(butterfly.callers().children()).extracting(c -> c.methodName()).containsExactly("save", "run");
        assertThat(butterfly.callers().children().get(0).totalValue()).isEqualTo(40 * 10 * MS);
        assertThat(butterfly.callees().children()).singleElement()
                .satisfies(c -> assertThat(c.methodName()).isEqualTo("exec"));
    }

    @Test
    void differentialFlameMarksRegressionsAndImprovements() {
        ProfileData baseline = new Profile()
                .cpu("app.Main.run > app.Fast.work", 50, 0)
                .cpu("app.Main.run > app.Slow.work", 50, 100)
                .build();
        ProfileData target = new Profile()
                .cpu("app.Main.run > app.Fast.work", 20, 0)
                .cpu("app.Main.run > app.Slow.work", 80, 100)
                .build();

        DiffFlameNodeDto root = CallTreeEngine.diff(baseline,
                CallTreeEngine.flameTree(baseline, ProfileMetric.CPU, 0, 1e9, -1), target,
                CallTreeEngine.flameTree(target, ProfileMetric.CPU, 0, 1e9, -1), 0, 1000);

        DiffFlameNodeDto main = root.children().get(0);
        DiffFlameNodeDto slow = main.children().stream().filter(c -> c.className().equals("app.Slow")).findFirst()
                .orElseThrow();
        DiffFlameNodeDto fast = main.children().stream().filter(c -> c.className().equals("app.Fast")).findFirst()
                .orElseThrow();
        assertThat(slow.deltaPercent()).isEqualTo(30.0);
        assertThat(fast.deltaPercent()).isEqualTo(-30.0);
        assertThat(main.deltaPercent()).isZero();
    }

    @Test
    void wallTimeCountsEventsOnceAndClassifiesWaits() {
        Profile profile = new Profile();
        int query = profile.stack("app.Svc.load > org.postgresql.core.PGStream.receive > java.net.Socket.read");
        int lock = profile.stack("app.Svc.update > app.Svc.lockedPart");
        // a 100 ms socket read; the native samples inside it must not add to it
        profile.builder.event(ProfileData.EventKind.SOCKET_READ, 0, 100, profile.thread, query, "db:5432");
        for (int t = 10; t < 100; t += 20) {
            profile.builder.sample(t, profile.thread, query, ProfileData.SampleKind.NATIVE, 20 * MS);
        }
        profile.builder.event(ProfileData.EventKind.MONITOR_ENTER, 200, 50, profile.thread, lock, "app.Ledger");
        ProfileData data = profile.build();

        SubsystemBreakdownDto wall = SubsystemBreakdownBuilder.build("p", data, ProfileMetric.WALL, 0, 1e9, -1);

        assertThat(wall.totalValue()).isEqualTo(150 * MS);
        assertThat(wall.subsystems()).extracting(SubsystemShareDto::subsystem)
                .containsExactly(Subsystem.JDBC, Subsystem.LOCKS);
        assertThat(wall.traced()).anyMatch(t -> t.type().equals("jdk.SocketRead") && t.topDetail().equals("db:5432"));

        // a window cuts events: [50, 225) holds 50 ms of the read and 25 ms of the lock
        SubsystemBreakdownDto window = SubsystemBreakdownBuilder.build("p", data, ProfileMetric.WALL, 50, 225, -1);
        assertThat(window.totalValue()).isEqualTo(75 * MS);
    }
}
