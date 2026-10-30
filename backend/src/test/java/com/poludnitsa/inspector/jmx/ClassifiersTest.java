package com.poludnitsa.inspector.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.GcGeneration;
import com.poludnitsa.inspector.api.dto.MemoryPoolKind;
import com.poludnitsa.inspector.api.dto.MemoryPoolType;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.common.JvmTypeNames;
import com.poludnitsa.inspector.common.PackageCategorizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ClassifiersTest {

    @ParameterizedTest
    @CsvSource({
            "G1 Young Generation, YOUNG, true",
            "G1 Old Generation, OLD, true",
            "G1 Concurrent GC, CONCURRENT, true",
            "PS Scavenge, YOUNG, true",
            "PS MarkSweep, OLD, true",
            "Copy, YOUNG, true",
            "MarkSweepCompact, OLD, true",
            "ZGC Cycles, OLD, false",
            "ZGC Pauses, OLD, true",
            "ZGC Minor Cycles, YOUNG, false",
            "ZGC Major Pauses, OLD, true",
            "Shenandoah Cycles, OLD, false",
            "Shenandoah Pauses, OLD, true",
            "Epsilon, UNKNOWN, true"
    })
    void collectors(String name, GcGeneration generation, boolean stopTheWorld) {
        assertThat(GcCollectorClassifier.generation(name)).isEqualTo(generation);
        assertThat(GcCollectorClassifier.stopTheWorld(name)).isEqualTo(stopTheWorld);
    }

    @ParameterizedTest
    @CsvSource({
            "G1 Eden Space, HEAP, EDEN",
            "G1 Survivor Space, HEAP, SURVIVOR",
            "G1 Old Gen, HEAP, OLD",
            "PS Old Gen, HEAP, OLD",
            "Tenured Gen, HEAP, OLD",
            "ZGC Old Generation, HEAP, OLD",
            "ZHeap, HEAP, OTHER_HEAP",
            "Metaspace, NON_HEAP, METASPACE",
            "Compressed Class Space, NON_HEAP, COMPRESSED_CLASS_SPACE",
            "CodeHeap 'profiled nmethods', NON_HEAP, CODE_CACHE",
            "CodeCache, NON_HEAP, CODE_CACHE"
    })
    void pools(String name, MemoryPoolType type, MemoryPoolKind kind) {
        assertThat(MemoryPoolClassifier.kind(name, type)).isEqualTo(kind);
    }

    @ParameterizedTest
    @CsvSource({
            "[B, byte[]",
            "[[J, long[][]",
            "[Ljava.lang.String;, java.lang.String[]",
            "java.util.HashMap$Node, java.util.HashMap$Node",
            "byte[], byte[]"
    })
    void typeNames(String jvmName, String javaName) {
        assertThat(JvmTypeNames.toJavaName(jvmName)).isEqualTo(javaName);
    }

    @ParameterizedTest
    @CsvSource({
            "byte[], PRIMITIVE_OR_ARRAY",
            "java.lang.String[], JDK",
            "javax.management.ObjectName, JDK",
            "jakarta.servlet.http.HttpSession, JAKARTA_EE",
            "io.undertow.server.session.InMemorySessionManager$SessionImpl, WILDFLY",
            "org.jboss.modules.ModuleClassLoader, WILDFLY",
            "com.hazelcast.client.impl.spi.impl.ClientInvocation, HAZELCAST",
            "org.springframework.context.support.GenericApplicationContext, THIRD_PARTY",
            "com.acme.orders.OrderCache$Entry, APPLICATION",
            "Main, PRIMITIVE_OR_ARRAY"
    })
    void packages(String className, PackageCategory category) {
        assertThat(PackageCategorizer.categorize(className)).isEqualTo(category);
    }
}
