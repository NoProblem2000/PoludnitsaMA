package com.poludnitsa.inspector.inspection.rules.jvm;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.heap.LongIntHashMap;
import com.poludnitsa.inspector.inspection.FindingBuilder;
import com.poludnitsa.inspector.inspection.InspectionContext;
import com.poludnitsa.inspector.inspection.LeakInspectionRule;
import com.poludnitsa.inspector.inspection.Tally;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.PrimitiveArrayInstance;
import org.springframework.stereotype.Component;

/**
 * Identical {@code String}s and {@code byte[]}s held in many copies.
 *
 * <p>Two passes keep the inspector's own memory low: pass 1 counts a 64-bit content fingerprint per object in a
 * primitive map; pass 2 collects details only for fingerprints that occur at least {@code duplicateStringMinCopies}
 * times. Copies sharing one value array (already deduplicated by G1) cost only the String object and are counted so.
 */
@Component
public class DuplicateStringsAndByteArraysInspector implements LeakInspectionRule {

    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MIN_ARRAY_BYTES = 256;
    private static final int MAX_ARRAY_BYTES = 1 << 20;
    private static final int MAX_ARRAYS_SCANNED = 500_000;
    private static final long MAX_ARRAY_BYTES_READ = 512L << 20;
    private static final int MIN_ARRAY_COPIES = 10;
    private static final int TOP_GROUPS = 15;
    private static final int SAMPLES_PER_GROUP = 40;

    @Override
    public String id() {
        return "jvm.duplicate-strings";
    }

    @Override
    public String title() {
        return "Дубликаты строк и byte[]";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JVM_CORE;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return !context.reader().classesNamed("java.lang.String").isEmpty();
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        List<InspectionFindingDto> findings = new ArrayList<>();
        strings(context, findings);
        byteArrays(context, findings);
        return findings;
    }

    // ---------------------------------------------------------------- strings

    private void strings(InspectionContext context, List<InspectionFindingDto> findings) {
        HeapReader reader = context.reader();
        List<JavaClass> stringClass = reader.classesNamed("java.lang.String");
        int limit = Math.min(context.settings().maxStringsScanned(), HeapReader.instanceCount(stringClass));
        int minCopies = context.settings().duplicateStringMinCopies();
        LongIntHashMap counts = new LongIntHashMap(limit);
        int scanned = 0;
        for (Instance string : HeapReader.instancesOf(stringClass)) {
            if (scanned++ >= limit) {
                context.markTruncated();
                break;
            }
            if ((scanned & 4095) == 0 && context.outOfBudget()) {
                return;
            }
            long fingerprint = stringFingerprint(reader, string);
            if (fingerprint != 0) {
                counts.increment(fingerprint);
            }
        }

        Map<Long, Group> groups = new HashMap<>();
        int second = 0;
        for (Instance string : HeapReader.instancesOf(stringClass)) {
            if (second++ >= scanned) {
                break;
            }
            if ((second & 4095) == 0 && context.outOfBudget()) {
                break;
            }
            long fingerprint = stringFingerprint(reader, string);
            if (fingerprint != 0 && counts.get(fingerprint) >= minCopies) {
                Instance value = HeapReader.objectField(string, "value");
                groups.computeIfAbsent(fingerprint, f -> new Group(display(reader.string(string, 80))))
                        .add(string, value);
            }
        }
        report(context, findings, groups, "strings", true);
    }

    private long stringFingerprint(HeapReader reader, Instance string) {
        Instance value = HeapReader.objectField(string, "value");
        if (!(value instanceof PrimitiveArrayInstance array) || array.getLength() > MAX_STRING_BYTES) {
            return 0;
        }
        long coder = HeapReader.longField(string, "coder", 0);
        if (!"byte[]".equals(HeapReader.className(array))) {
            String text = reader.string(string, MAX_STRING_BYTES);
            return text == null ? 0 : fnv(text.getBytes(StandardCharsets.UTF_16LE), 2) | 1;
        }
        return fnv(reader.bytes(array, array.getLength()), coder) | 1;
    }

    // ---------------------------------------------------------------- byte arrays

    private void byteArrays(InspectionContext context, List<InspectionFindingDto> findings) {
        HeapReader reader = context.reader();
        List<JavaClass> byteArray = reader.classesNamed("byte[]");
        LongIntHashMap counts = new LongIntHashMap(1 << 16);
        int scanned = 0;
        long bytesRead = 0;
        for (Instance instance : HeapReader.instancesOf(byteArray)) {
            if (scanned >= MAX_ARRAYS_SCANNED || bytesRead >= MAX_ARRAY_BYTES_READ) {
                context.markTruncated();
                break;
            }
            if (!(instance instanceof PrimitiveArrayInstance array) || array.getLength() < MIN_ARRAY_BYTES
                    || array.getLength() > MAX_ARRAY_BYTES) {
                continue;
            }
            if ((++scanned & 1023) == 0 && context.outOfBudget()) {
                return;
            }
            bytesRead += array.getLength();
            counts.increment(fnv(reader.bytes(array, array.getLength()), 7) | 1);
        }

        Map<Long, Group> groups = new HashMap<>();
        int second = 0;
        for (Instance instance : HeapReader.instancesOf(byteArray)) {
            if (second >= scanned) {
                break;
            }
            if (!(instance instanceof PrimitiveArrayInstance array) || array.getLength() < MIN_ARRAY_BYTES
                    || array.getLength() > MAX_ARRAY_BYTES) {
                continue;
            }
            second++;
            long fingerprint = fnv(reader.bytes(array, array.getLength()), 7) | 1;
            if (counts.get(fingerprint) >= MIN_ARRAY_COPIES) {
                groups.computeIfAbsent(fingerprint, f -> new Group(hexPreview(reader.bytes(array, 16), array)))
                        .add(instance, null);
            }
        }
        // String contents are reported by the string pass.
        groups.values().removeIf(group -> group.samples.stream().anyMatch(sample ->
                reader.referrers(sample, 4).stream().anyMatch(r -> r.holderClass().equals("java.lang.String"))));
        report(context, findings, groups, "byte-arrays", false);
    }

    // ---------------------------------------------------------------- reporting

    private void report(InspectionContext context, List<InspectionFindingDto> findings, Map<Long, Group> groups,
                        String key, boolean strings) {
        List<Group> ranked = new ArrayList<>(groups.values());
        ranked.sort((a, b) -> Long.compare(b.waste(), a.waste()));
        long totalWaste = ranked.stream().mapToLong(Group::waste).sum();
        if (totalWaste < context.settings().minReportedWaste().toBytes()) {
            return;
        }
        HeapReader reader = context.reader();
        long copies = ranked.stream().mapToLong(group -> group.count).sum();
        String topField = null;
        FindingBuilder finding = context.finding(this, key)
                .severity(context.severityByShare(totalWaste, 0.05, 0.15))
                .title(String.format(strings
                                ? "Дубликаты строк: %s потеряно (%.1f%% кучи) в %,d группах одинакового текста"
                                : "Дубликаты byte[]: %s потеряно (%.1f%% кучи) в %,d группах одинакового содержимого",
                        Bytes.format(totalWaste), 100 * context.shareOfHeap(totalWaste), ranked.size()))
                .wasted(totalWaste)
                .affected(copies)
                .pathOf(ranked.get(0).samples.get(0));
        for (Group group : ranked.subList(0, Math.min(TOP_GROUPS, ranked.size()))) {
            Tally fields = new Tally();
            for (Instance sample : group.samples) {
                for (HeapReader.Referrer referrer : reader.referrers(sample, 2)) {
                    fields.add(referrer.describe(), 0);
                }
            }
            String producers = String.join("; ", fields.top(3).stream()
                    .map(entry -> entry.key() + " (" + entry.count() + " из " + group.samples.size() + ")").toList());
            if (topField == null && !fields.isEmpty()) {
                topField = fields.top(1).get(0).key();
            }
            finding.evidence(String.format("%s × %,d копий → потеряно %s; поля: %s", group.label, group.count,
                    Bytes.format(group.waste()), producers.isEmpty() ? "—" : producers));
        }
        finding.suspect(strings ? "java.lang.String" : "byte[]", topField);
        findings.add(finding.why(strings ? WHY_STRINGS : WHY_BYTES).fix(strings ? FIX_STRINGS : FIX_BYTES).build());
    }

    private static final class Group {
        final String label;
        final Set<Long> arrays = new HashSet<>();
        final List<Instance> samples = new ArrayList<>();
        long count;
        long objectBytes;
        long arrayBytes;
        long firstObject = -1;
        long firstArray = -1;

        Group(String label) {
            this.label = label;
        }

        void add(Instance object, Instance array) {
            count++;
            objectBytes += object.getSize();
            if (firstObject < 0) {
                firstObject = object.getSize();
            }
            if (array != null && arrays.add(array.getInstanceId())) {
                arrayBytes += array.getSize();
                if (firstArray < 0) {
                    firstArray = array.getSize();
                }
            }
            if (samples.size() < SAMPLES_PER_GROUP) {
                samples.add(object);
            }
        }

        /** Everything except one copy (of the object and of its value array). */
        long waste() {
            return objectBytes - Math.max(firstObject, 0) + arrayBytes - Math.max(firstArray, 0);
        }
    }

    private static String display(String text) {
        if (text == null) {
            return "\"?\"";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length() && i < 80; i++) {
            char c = text.charAt(i);
            out.append(Character.isISOControl(c) ? '·' : c);
        }
        return out.append(text.length() >= 80 ? "…\"" : "\"").toString();
    }

    private static String hexPreview(byte[] head, PrimitiveArrayInstance array) {
        StringBuilder out = new StringBuilder("byte[").append(array.getLength()).append("] ");
        boolean zeros = true;
        for (byte b : head) {
            out.append(String.format("%02x", b & 0xFF));
            zeros &= b == 0;
        }
        out.append('…');
        // Identical all-zero arrays are usually buffers allocated up front and never (or not yet) filled.
        return zeros ? out.append(" (нулевой буфер: выделен заранее и не заполнен?)").toString() : out.toString();
    }

    private static long fnv(byte[] bytes, long salt) {
        long hash = 0xcbf29ce484222325L ^ salt;
        for (byte b : bytes) {
            hash ^= b & 0xFF;
            hash *= 0x100000001b3L;
        }
        return hash ^ ((long) bytes.length << 32);
    }

    private static final String WHY_STRINGS = """
            Одинаковый текст хранится в памяти тысячами независимых копий. Каждое чтение из БД (JDBC/Hibernate), \
            разбор JSON/XML, HTTP-заголовков или CSV создаёт новый объект String, даже если значение — один из \
            десятка статусов, кодов валют, типов документов или имён полей. Каждая копия стоит заголовок String \
            (24 байта) плюс свой массив символов.

            В фактах для каждого повторяющегося значения указаны поля, которые держат копии: это и есть место, \
            где данные заходят в приложение и где дубликаты стоит схлопнуть. Если G1 String Deduplication уже \
            включён, массивы символов общие, а потеря — только объекты String.""";

    private static final String FIX_STRINGS = """
            // 1. Повторяющиеся значения из фиксированного словаря — enum, а не String
            public enum OrderStatus { NEW, PAID, SHIPPED }

            @Enumerated(EnumType.STRING)
            private OrderStatus status;                  // вместо private String status;

            // 2. Открытый, но небольшой словарь — свой интернер (без утечек String.intern() в старых JDK)
            public final class Dictionary {
                private static final Map<String, String> VALUES = new ConcurrentHashMap<>();
                public static String canonical(String value) {
                    return value == null ? null : VALUES.computeIfAbsent(value, v -> v);
                }
            }
            this.currency = Dictionary.canonical(row.getString("currency"));

            // 3. Jackson: имена полей и так интернируются; значения — через @JsonDeserialize(using = ...)
            //    или enum-поля в DTO.

            // 4. Дешёвое средство «без кода» для G1 (JDK 18+ также ZGC/Shenandoah):
            //    -XX:+UseStringDeduplication   (общий массив символов; сами объекты String остаются)""";

    private static final String WHY_BYTES = """
            Одинаковое двоичное содержимое (файлы, изображения, сериализованные сообщения, шаблоны документов) \
            загружено в память многократно: каждый запрос заново читает его с диска, из БД или по сети и держит \
            собственную копию — в кэше, в сессии или в полях DTO.""";

    private static final String FIX_BYTES = """
            // Загрузите неизменяемое содержимое один раз и раздавайте ссылку (или read-only ByteBuffer)
            @ApplicationScoped
            public class TemplateStore {
                private final Map<String, byte[]> templates = new ConcurrentHashMap<>();

                public byte[] template(String name) {
                    return templates.computeIfAbsent(name, this::load);   // одна копия на имя
                }
            }

            // Большие файлы не держите в памяти целиком — стримьте:
            return Response.ok((StreamingOutput) out -> Files.copy(path, out)).build();""";
}
