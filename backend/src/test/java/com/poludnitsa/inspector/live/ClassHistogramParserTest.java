package com.poludnitsa.inspector.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ClassHistogramParserTest {

    private static final String JDK17_OUTPUT = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:         48215        9126344  [B (java.base@17.0.9)
               2:         43901        1053624  java.lang.String (java.base@17.0.9)
               3:          3075         369000  java.lang.Class (java.base@17.0.9)
               4:          2210         141440  [Ljava.lang.Object; (java.base@17.0.9)
               5:           900          57600  com.acme.orders.OrderCache$Entry
               6:           300          19200  com.acme.orders.OrderCache$Entry
               7:            41           1312  com.acme.Api$$Lambda$102/0x0000000800c4b240
               8:             2            112  [[I (java.base@17.0.9)
               9:             1             16  jdk.internal.misc.Unsafe (java.base@17.0.9)
            Total         98645       10768648
            """;

    @Test
    void parsesRowsModulesAndTotal() {
        ClassHistogram histogram = ClassHistogramParser.parse(JDK17_OUTPUT, 42L);
        Map<String, HistogramEntry> byName = histogram.entries().stream()
                .collect(Collectors.toMap(HistogramEntry::className, Function.identity()));

        assertThat(histogram.timestampEpochMs()).isEqualTo(42L);
        assertThat(histogram.totalInstances()).isEqualTo(98645);
        assertThat(histogram.totalBytes()).isEqualTo(10768648);
        assertThat(byName.get("byte[]")).isEqualTo(new HistogramEntry("byte[]", 48215, 9126344, "java.base@17.0.9", 1));
        assertThat(byName.get("java.lang.Object[]").bytes()).isEqualTo(141440);
        assertThat(byName.get("int[][]").instances()).isEqualTo(2);
        assertThat(byName.get("com.acme.Api$$Lambda$102/0x0000000800c4b240").module()).isNull();
    }

    @Test
    void mergesSameClassFromDifferentClassLoaders() {
        HistogramEntry entry = ClassHistogramParser.parse(JDK17_OUTPUT, 0).entries().stream()
                .filter(e -> e.className().equals("com.acme.orders.OrderCache$Entry"))
                .findFirst().orElseThrow();

        assertThat(entry.instances()).isEqualTo(1200);
        assertThat(entry.bytes()).isEqualTo(76800);
        assertThat(entry.definitionCount()).isEqualTo(2);
    }

    @Test
    void ordersByBytesDescending() {
        ClassHistogram histogram = ClassHistogramParser.parse(JDK17_OUTPUT, 0);

        assertThat(histogram.entries()).extracting(HistogramEntry::className)
                .startsWith("byte[]", "java.lang.String", "java.lang.Class");
        assertThat(histogram.entries()).hasSize(8);
    }

    @Test
    void sumsRowsWhenTotalLineIsMissing() {
        String truncated = JDK17_OUTPUT.substring(0, JDK17_OUTPUT.indexOf("Total"));

        ClassHistogram histogram = ClassHistogramParser.parse(truncated, 0);

        assertThat(histogram.totalInstances()).isEqualTo(98645);
        assertThat(histogram.totalBytes()).isEqualTo(10768648);
    }

    @Test
    void handlesWindowsLineEndings() {
        ClassHistogram histogram = ClassHistogramParser.parse(JDK17_OUTPUT.replace("\n", "\r\n"), 0);

        assertThat(histogram.entries()).hasSize(8);
        assertThat(histogram.totalBytes()).isEqualTo(10768648);
    }

    @Test
    void rejectsUnexpectedOutput() {
        assertThatThrownBy(() -> ClassHistogramParser.parse("Permission denied", 0))
                .isInstanceOf(ClassHistogramParser.HistogramParseException.class)
                .hasMessageContaining("Permission denied");
    }
}
