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
    void liveStreamMessageRoundTrips() throws Exception {
        var suspect = new ClassGrowthSuspectDto("com.acme.Cache$Entry", PackageCategory.APPLICATION, 1000, 32_000,
                50, 1600, 87, true, 3, List.of(new GrowthWindowDto(5, 12, 1600, 50, 0.9, 0.97)));
        var jfr = new JfrAllocationStatsDto(true, "jvm-leak-inspector-alloc-1-abc", 1L, 2L, 1.5e6,
                List.of(new AllocatingClassDto("byte[]", PackageCategory.PRIMITIVE_OR_ARRAY, 1e6, 40)), null);
        var payload = new LiveMetricsPayloadDto(
                4711L, 1_700_000_000_000L,
                new MemoryUsageDto(256L, 128L, 256L, 1024L),
                new MemoryUsageDto(0L, 64L, 80L, -1L),
                List.of(new MemoryPoolDto("Metaspace", MemoryPoolType.NON_HEAP, MemoryPoolKind.METASPACE,
                        50L, 60L, -1L, null)),
                List.of(new GcCollectorMetricsDto("G1 Young Generation", GcGeneration.YOUNG, true,
                        10, 120, 1, 12, 12L)),
                1.5, 12.0, 2.5e6, null, 35L, 10_000L, List.of(suspect), jfr);
        var message = LiveStreamMessageDto.metrics(payload);

        String json = mapper.writeValueAsString(message);

        assertThat(json).contains("\"type\":\"METRICS\"", "\"histogramTimestampEpochMs\":null",
                "\"leakProbabilityScore\":87", "\"kind\":\"METASPACE\"");
        assertThat(mapper.readValue(json, LiveStreamMessageDto.class)).isEqualTo(message);
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
                "Ctx <- ThreadLocalMap$Entry.value <- Thread.threadLocals [GC Root: thread object]",
                List.of("Потоков: 4"), "why", "threadLocal.remove();");

        assertThat(mapper.readValue(mapper.writeValueAsString(finding), InspectionFindingDto.class)).isEqualTo(finding);
    }

    @Test
    void remoteRequestNeverPrintsPassword() {
        var request = new RemoteJmxConnectRequestDto("127.0.0.1:9010", null, "monitor", "s3cret");

        assertThat(request.toString()).doesNotContain("s3cret");
        assertThat(request.isExactlyOneEndpoint()).isTrue();
        assertThat(new RemoteJmxConnectRequestDto("h:1", "service:jmx:x", null, null).isExactlyOneEndpoint()).isFalse();
    }

    @Test
    void leakScoreOutOfRangeIsRejected() {
        assertThatThrownBy(() -> new ClassGrowthSuspectDto("X", PackageCategory.JDK, 0, 0, 0, 0, 101, false, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void netbeansHeapFactoryIsOnClasspath() throws Exception {
        assertThat(Class.forName("org.netbeans.lib.profiler.heap.HeapFactory")).isNotNull();
    }
}
