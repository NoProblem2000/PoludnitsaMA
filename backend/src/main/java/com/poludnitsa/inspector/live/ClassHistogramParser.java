package com.poludnitsa.inspector.live;

import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.JvmTypeNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the text produced by {@code GC.class_histogram} on Java 9+:
 *
 * <pre>
 *  num     #instances         #bytes  class name (module)
 * -------------------------------------------------------
 *    1:         48215        9126344  [B (java.base@17.0.9)
 *    2:          3075         369000  java.lang.Class (java.base@17.0.9)
 *    3:           120           3840  com.acme.OrderCache$Entry
 *    4:            41           1312  com.acme.Api$$Lambda$102/0x0000000800c4b240
 * Total         51451        9500496
 * </pre>
 *
 * Rows with the same class name come from different class loaders and are merged; their number is kept as
 * {@link HistogramEntry#definitionCount()}.
 */
public final class ClassHistogramParser {

    private static final Pattern ROW =
            Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(?:\\s+\\((.*)\\))?\\s*$");
    private static final Pattern TOTAL = Pattern.compile("^\\s*Total\\s+(\\d+)\\s+(\\d+)\\s*$");

    private ClassHistogramParser() {
    }

    public static ClassHistogram parse(String output, long timestampEpochMs) {
        if (output == null) {
            throw new HistogramParseException("empty GC.class_histogram output");
        }
        Map<String, Accumulator> byName = new HashMap<>(8192);
        long totalInstances = -1;
        long totalBytes = -1;
        int rows = 0;
        for (String line : (Iterable<String>) output.lines()::iterator) {
            Matcher row = ROW.matcher(line);
            if (row.matches()) {
                rows++;
                String className = JvmTypeNames.toJavaName(row.group(3));
                Accumulator accumulator = byName.computeIfAbsent(className, name -> new Accumulator(row.group(4)));
                accumulator.instances += Long.parseLong(row.group(1));
                accumulator.bytes += Long.parseLong(row.group(2));
                accumulator.definitions++;
                continue;
            }
            Matcher total = TOTAL.matcher(line);
            if (total.matches()) {
                totalInstances = Long.parseLong(total.group(1));
                totalBytes = Long.parseLong(total.group(2));
            }
        }
        if (rows == 0 && totalInstances < 0) {
            String head = output.strip();
            throw new HistogramParseException("unexpected GC.class_histogram output: "
                    + (head.length() > 200 ? head.substring(0, 200) + "..." : head));
        }
        List<HistogramEntry> entries = new ArrayList<>(byName.size());
        long sumInstances = 0;
        long sumBytes = 0;
        for (Map.Entry<String, Accumulator> e : byName.entrySet()) {
            Accumulator a = e.getValue();
            entries.add(new HistogramEntry(e.getKey(), a.instances, a.bytes, a.module, a.definitions));
            sumInstances += a.instances;
            sumBytes += a.bytes;
        }
        entries.sort(Comparator.comparingLong(HistogramEntry::bytes).reversed()
                .thenComparing(HistogramEntry::className));
        return new ClassHistogram(timestampEpochMs, entries,
                totalInstances >= 0 ? totalInstances : sumInstances,
                totalBytes >= 0 ? totalBytes : sumBytes);
    }

    private static final class Accumulator {
        final String module;
        long instances;
        long bytes;
        int definitions;

        Accumulator(String module) {
            this.module = module;
        }
    }

    public static final class HistogramParseException extends InspectorException {
        HistogramParseException(String message) {
            super(502, "HISTOGRAM_PARSE_FAILED", message);
        }
    }
}
