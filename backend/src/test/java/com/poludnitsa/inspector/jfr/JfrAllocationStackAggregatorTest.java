package com.poludnitsa.inspector.jfr;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.AllocationFrameDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class JfrAllocationStackAggregatorTest {

    private static final AllocationFrameDto GROW = new AllocationFrameDto("java.util.ArrayList", "grow", 237, false);
    private static final AllocationFrameDto PROCESS = new AllocationFrameDto("com.acme.OrderService", "process", 142, true);
    private static final AllocationFrameDto IMPORT = new AllocationFrameDto("com.acme.ImportJob", "run", 77, true);
    private static final AllocationFrameDto POOL = new AllocationFrameDto("java.util.concurrent.ThreadPoolExecutor",
            "runWorker", 1144, false);

    @Test
    void hotspotIsTheFirstApplicationFrameBelowJdkInternals() {
        JfrAllocationStackAggregator aggregator = new JfrAllocationStackAggregator(3, 100);
        aggregator.onAllocation("java.lang.Object[]", List.of(GROW, PROCESS, POOL), 3_000, 1_000);
        aggregator.onAllocation("java.lang.Object[]", List.of(GROW, IMPORT, POOL), 1_000, 1_001);
        aggregator.onAllocation("java.lang.Object[]", List.of(GROW, PROCESS, POOL), 2_000, 1_002);
        aggregator.onAllocation("byte[]", List.of(POOL), 9_999, 1_003);
        aggregator.completePoll();

        JfrAllocationStackAggregator.ClassStacks stacks = aggregator.stacks("java.lang.Object[]", 10);

        assertThat(stacks.weightBytes()).isEqualTo(6_000);
        assertThat(stacks.samples()).isEqualTo(3);
        assertThat(stacks.hotspots()).hasSize(2);
        assertThat(stacks.hotspots().get(0).frame()).isEqualTo(PROCESS);
        assertThat(stacks.hotspots().get(0).weightBytes()).isEqualTo(5_000);
        assertThat(stacks.hotspots().get(0).share()).isEqualTo(5_000.0 / 6_000);
        assertThat(stacks.stacks().get(0).frames()).containsExactly(GROW, PROCESS, POOL);
        assertThat(stacks.stacks().get(0).samples()).isEqualTo(2);
    }

    @Test
    void stacksWithoutApplicationFramesFallBackToTheFirstNonJdkThenTheTopFrame() {
        AllocationFrameDto undertow = new AllocationFrameDto("io.undertow.server.Connectors", "executeRootHandler", 402,
                false);
        assertThat(JfrAllocationStackAggregator.hotspot(List.of(GROW, undertow, POOL))).isEqualTo(undertow);
        assertThat(JfrAllocationStackAggregator.hotspot(List.of(GROW, POOL))).isEqualTo(GROW);
    }

    @Test
    void oldPollsLeaveTheWindowAndOverlappingEventsAreSkipped() {
        JfrAllocationStackAggregator aggregator = new JfrAllocationStackAggregator(2, 100);
        aggregator.onAllocation("X", List.of(PROCESS), 100, 10);
        aggregator.completePoll();
        // the next transferred chunk overlaps: an event at or before the last processed time is ignored
        aggregator.onAllocation("X", List.of(PROCESS), 100, 10);
        aggregator.onAllocation("X", List.of(IMPORT), 50, 20);
        aggregator.completePoll();
        assertThat(aggregator.stacks("X", 10).weightBytes()).isEqualTo(150);

        aggregator.onAllocation("Y", List.of(IMPORT), 10, 30);
        aggregator.completePoll();
        // only the last two polls remain
        assertThat(aggregator.stacks("X", 10).weightBytes()).isEqualTo(50);
        assertThat(aggregator.stacks("missing", 10).hotspots()).isEmpty();
    }

    @Test
    void distinctStacksPerClassAreBounded() {
        JfrAllocationStackAggregator aggregator = new JfrAllocationStackAggregator(1, 2);
        for (int line = 1; line <= 5; line++) {
            aggregator.onAllocation("X", List.of(new AllocationFrameDto("com.acme.A", "m", line, true)), 1, line);
        }
        aggregator.completePoll();
        assertThat(aggregator.stacks("X", 10).stacks()).hasSize(2);
    }
}
