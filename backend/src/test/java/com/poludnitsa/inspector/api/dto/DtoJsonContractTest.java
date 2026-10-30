package com.poludnitsa.inspector.api.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class DtoJsonContractTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void liveMetricsPayloadRoundTrips() throws Exception {
        var payload = new LiveMetricsPayloadDto(
                4711L, 1_700_000_000_000L,
                new MemoryUsageDto(256L, 128L, 256L, 1024L),
                new MemoryUsageDto(0L, 64L, 80L, -1L),
                List.of(new MemoryPoolDto("Metaspace", MemoryPoolType.NON_HEAP, 50L, 60L, -1L, null)),
                List.of(new GcCollectorMetricsDto("G1 Young Generation", GcGeneration.YOUNG, 10, 120, 1, 12, 12L)),
                1.5, null,
                List.of(new ClassGrowthSuspectDto("com.acme.Cache$Entry", PackageCategory.APPLICATION,
                        1000, 32_000, 50, 1600, 87)));

        String json = mapper.writeValueAsString(payload);

        assertThat(json).contains("\"histogramTimestampEpochMs\":null", "\"leakProbabilityScore\":87");
        assertThat(mapper.readValue(json, LiveMetricsPayloadDto.class)).isEqualTo(payload);
    }

    @Test
    void instantsAreIsoStrings() throws Exception {
        var error = new ApiErrorDto(Instant.parse("2026-10-30T10:15:30Z"), 507, "INSUFFICIENT_DISK_SPACE", "m", "/p");

        assertThat(mapper.writeValueAsString(error)).contains("\"timestamp\":\"2026-10-30T10:15:30Z\"");
    }

    @Test
    void findingRoundTripsWithGcRootPath() throws Exception {
        var finding = new InspectionFindingDto("r1#42", "jakarta.threadlocal-in-managed-pool", Severity.CRITICAL,
                FindingCategory.JAKARTA_WILDFLY, "ThreadLocal leak", 1L << 20, 3, "java.lang.ThreadLocal$ThreadLocalMap",
                "table", List.of(new GcRootPathStepDto(1, "java.lang.Thread", "threadLocals", true, "thread object")),
                "why", "threadLocal.remove();");

        assertThat(mapper.readValue(mapper.writeValueAsString(finding), InspectionFindingDto.class)).isEqualTo(finding);
    }

    @Test
    void leakScoreOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new ClassGrowthSuspectDto("X", PackageCategory.JDK, 0, 0, 0, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void netbeansHeapFactoryIsOnClasspath() throws Exception {
        assertThat(Class.forName("org.netbeans.lib.profiler.heap.HeapFactory")).isNotNull();
    }
}
