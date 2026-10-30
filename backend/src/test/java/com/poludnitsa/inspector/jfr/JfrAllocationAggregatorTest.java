package com.poludnitsa.inspector.jfr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.poludnitsa.inspector.api.dto.AllocatingClassDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import org.junit.jupiter.api.Test;

class JfrAllocationAggregatorTest {

    private final JfrAllocationAggregator aggregator = new JfrAllocationAggregator(5);

    @Test
    void firstPollOnlySetsBaselines() {
        aggregator.onThreadAllocation(1, 1_000_000, 1_000);
        aggregator.completePoll();

        assertThat(aggregator.allocationRateBytesPerSec()).isNull();
        assertThat(aggregator.lastEventEpochMs()).isEqualTo(1_000);
    }

    @Test
    void rateIsSumOfThreadDeltasOverCoveredTime() {
        aggregator.onThreadAllocation(1, 1_000_000, 1_000);
        aggregator.onThreadAllocation(2, 5_000_000, 1_000);
        aggregator.completePoll();

        aggregator.onThreadAllocation(1, 3_000_000, 6_000);
        aggregator.onThreadAllocation(2, 13_000_000, 11_000);
        aggregator.completePoll();

        // (2 MB + 8 MB) over 10 s
        assertThat(aggregator.allocationRateBytesPerSec()).isCloseTo(1_000_000.0, offset(0.1));
    }

    @Test
    void overlappingChunksAreNotCountedTwice() {
        aggregator.onThreadAllocation(1, 1_000_000, 1_000);
        aggregator.completePoll();
        aggregator.onThreadAllocation(1, 2_000_000, 2_000);
        aggregator.completePoll();

        aggregator.onThreadAllocation(1, 2_000_000, 2_000);
        aggregator.onThreadAllocation(1, 4_000_000, 4_000);
        aggregator.completePoll();

        assertThat(aggregator.allocationRateBytesPerSec()).isCloseTo(1_000_000.0, offset(0.1));
    }

    @Test
    void topClassesFromSampleWeights() {
        aggregator.onAllocationSample("byte[]", 4_000_000, 1_000);
        aggregator.onAllocationSample("com.acme.Order", 1_000_000, 2_000);
        aggregator.onAllocationSample("byte[]", 6_000_000, 3_000);
        aggregator.completePoll();
        aggregator.onAllocationSample("byte[]", 10_000_000, 3_000);
        aggregator.onAllocationSample("com.acme.Order", 2_000_000, 5_000);
        aggregator.completePoll();

        assertThat(aggregator.topAllocatingClasses()).extracting(AllocatingClassDto::className)
                .containsExactly("byte[]", "com.acme.Order");
        AllocatingClassDto order = aggregator.topAllocatingClasses().get(1);
        // 3 MB over (2 s first window + 2 s second window); the duplicate byte[] sample at t=3000 is skipped
        assertThat(order.bytesPerSec()).isCloseTo(750_000.0, offset(0.1));
        assertThat(order.samples()).isEqualTo(2);
        assertThat(order.packageCategory()).isEqualTo(PackageCategory.APPLICATION);
    }
}
