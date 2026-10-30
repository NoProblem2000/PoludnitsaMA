package com.poludnitsa.inspector.inspection.rules.jvm;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.inspection.FindingBuilder;
import com.poludnitsa.inspector.inspection.InspectionContext;
import com.poludnitsa.inspector.inspection.LeakInspectionRule;
import com.poludnitsa.inspector.inspection.Tally;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.netbeans.lib.profiler.heap.FieldValue;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.springframework.stereotype.Component;

/**
 * JDK collections that retain more than {@code collectionHeapShare} of the heap or hold more than
 * {@code collectionMinElements} elements, with their owning field, element types and a check of map keys for missing
 * {@code equals()/hashCode()} (logically equal keys stored as separate entries).
 */
@Component
public class UnboundedCollectionAndCacheInspector implements LeakInspectionRule {

    static final List<String> COLLECTIONS = List.of(
            "java.util.HashMap", "java.util.concurrent.ConcurrentHashMap", "java.util.ArrayList",
            "java.util.concurrent.LinkedBlockingQueue", "java.util.LinkedList", "java.util.Hashtable",
            "java.util.TreeMap", "java.util.concurrent.ConcurrentLinkedQueue", "java.util.ArrayDeque",
            "java.util.concurrent.CopyOnWriteArrayList", "java.util.concurrent.ConcurrentSkipListMap",
            "java.util.WeakHashMap", "java.util.IdentityHashMap", "java.util.concurrent.LinkedBlockingDeque",
            "java.util.concurrent.ArrayBlockingQueue", "java.util.PriorityQueue", "java.util.Vector");
    private static final int KEY_SAMPLE = 2_000;
    private static final int MIN_ELEMENTS_WHEN_HEAVY = 1_000;
    private static final int ELEMENT_SAMPLE = 1_000;

    @Override
    public String id() {
        return "jvm.unbounded-collection";
    }

    @Override
    public String title() {
        return "Неограниченные коллекции и кэши";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JVM_CORE;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return true;
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        HeapReader reader = context.reader();
        long shareBytes = (long) (context.settings().collectionHeapShare() * context.totalHeapBytes());
        int minElements = context.settings().collectionMinElements();
        // An element costs at least a 4-byte reference: smaller collections cannot reach minElements.
        long precheck = Math.min(shareBytes, minElements * 4L);
        int cap = context.settings().maxInstancesPerRule();

        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        outer:
        for (String name : COLLECTIONS) {
            int scanned = 0;
            for (Instance collection : HeapReader.instancesOf(reader.withSubclasses(name))) {
                if (++scanned > cap) {
                    context.markTruncated();
                    break;
                }
                if ((scanned & 1023) == 0 && context.outOfBudget()) {
                    break outer;
                }
                long retained = collection.getRetainedSize();
                if (retained < precheck) {
                    continue;
                }
                long size = reader.size(collection);
                // A heavy collection with a handful of elements is a holder of a few big objects, not unbounded.
                boolean heavy = retained >= shareBytes && size >= MIN_ELEMENTS_WHEN_HEAVY;
                if (heavy || size >= minElements) {
                    candidates.put(collection.getInstanceId(), new Candidate(collection, size, retained));
                }
            }
        }

        List<Candidate> outermost = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            if (!nestedInAnother(reader, candidate.instance, candidates.keySet())
                    && !holdsClassObjects(reader, candidate.instance)) {
                outermost.add(candidate);
            }
        }
        outermost.sort((a, b) -> Long.compare(b.retained, a.retained));

        List<InspectionFindingDto> findings = new ArrayList<>();
        for (Candidate candidate : outermost.subList(0, Math.min(outermost.size(),
                context.settings().maxFindingsPerRule()))) {
            if (context.outOfBudget()) {
                break;
            }
            findings.add(describe(context, candidate, shareBytes));
        }
        return findings;
    }

    private InspectionFindingDto describe(InspectionContext context, Candidate candidate, long shareBytes) {
        HeapReader reader = context.reader();
        Instance collection = candidate.instance;
        String type = HeapReader.className(collection);
        HeapReader.Owner owner = reader.owner(collection);
        String ownerClass = owner == null ? "?" : owner.className();
        PackageCategory ownerCategory = PackageCategorizer.categorize(ownerClass);

        Tally keys = new Tally();
        Tally values = new Tally();
        KeyCheck keyCheck = new KeyCheck();
        boolean map = isMap(reader, collection);
        if (map) {
            reader.forEachMapEntry(collection, KEY_SAMPLE, (key, value, hash) -> {
                keys.add(HeapReader.className(key), 0);
                values.add(HeapReader.className(value), 0);
                keyCheck.add(reader, key, hash);
                return true;
            });
        } else {
            reader.forEachElement(collection, ELEMENT_SAMPLE, element -> {
                values.add(HeapReader.className(element), 0);
                return true;
            });
        }

        Severity severity = context.severityByShare(candidate.retained, context.settings().collectionHeapShare(),
                0.15);
        if (severity == Severity.INFO && candidate.size >= context.settings().collectionMinElements()) {
            severity = ownerCategory == PackageCategory.APPLICATION ? Severity.WARNING : Severity.INFO;
        }
        if (keyCheck.broken()) {
            severity = Severity.CRITICAL;
        }
        String where = owner == null ? GcRootPathResolver.simpleName(type) : owner.describe();
        FindingBuilder finding = context.finding(this, String.valueOf(collection.getInstanceId()))
                .severity(severity)
                .title(String.format("%s в %s: %,d элементов, %s (%.1f%% кучи)",
                        GcRootPathResolver.simpleName(type), where, Math.max(candidate.size, 0),
                        Bytes.format(candidate.retained), 100 * context.shareOfHeap(candidate.retained)))
                .wasted(candidate.retained)
                .affected(Math.max(candidate.size, 0))
                .suspect(ownerClass, owner == null ? null : owner.field())
                .pathOf(collection)
                .evidence("Коллекция: " + type + (owner != null ? ", владелец: " + owner.describe() : ""))
                .evidence(String.format("Элементов: %,d; удерживает %s; порог: %s или %,d элементов",
                        candidate.size, Bytes.format(candidate.retained), Bytes.format(shareBytes),
                        context.settings().collectionMinElements()));
        if (map) {
            keys.top(3).forEach(entry -> finding.evidence("Ключи: " + entry.format()));
        }
        values.top(3).forEach(entry -> finding.evidence((map ? "Значения: " : "Элементы: ") + entry.format()));
        keyCheck.evidence().forEach(finding::evidence);
        return finding.why(keyCheck.broken() ? WHY + WHY_KEYS : WHY).fix(FIX).build();
    }

    /**
     * {@code ClassLoader.classes} and similar lists of {@code java.lang.Class} "retain" the static state of every
     * class: that is not a growing collection (class loader leaks have their own inspection).
     */
    private static boolean holdsClassObjects(HeapReader reader, Instance collection) {
        HeapReader.Owner owner = reader.owner(collection);
        if (owner != null && HeapReader.isInstanceOf(owner.holder(), "java.lang.ClassLoader")) {
            return true;
        }
        boolean[] classes = {false};
        reader.forEachElement(collection, 8, element -> {
            classes[0] = "java.lang.Class".equals(HeapReader.className(element))
                    || reader.representedClass(element) != null;
            return !classes[0];
        });
        return classes[0];
    }

    private static boolean isMap(HeapReader reader, Instance collection) {
        boolean[] any = {false};
        reader.forEachMapEntry(collection, 1, (key, value, hash) -> {
            any[0] = true;
            return false;
        });
        String type = HeapReader.className(collection);
        return any[0] || type.endsWith("Map") || type.endsWith("Hashtable");
    }

    /** Whether another candidate holds this collection through JDK plumbing (HashSet.map, wrappers, nodes). */
    private static boolean nestedInAnother(HeapReader reader, Instance collection, Set<Long> candidateIds) {
        Deque<Instance> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(collection);
        for (int depth = 0; depth < 4 && !queue.isEmpty(); depth++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                for (HeapReader.Referrer referrer : reader.referrers(queue.poll(), 16)) {
                    long id = referrer.holder().getInstanceId();
                    if (id != collection.getInstanceId() && candidateIds.contains(id)) {
                        return true;
                    }
                    if (!referrer.staticField() && HeapReader.isPlumbing(referrer.holderClass()) && seen.add(id)) {
                        queue.add(referrer.holder());
                    }
                }
            }
        }
        return false;
    }

    private record Candidate(Instance instance, long size, long retained) {
    }

    /**
     * Application-class keys with identical field values stored as separate entries. Different stored hashes mean
     * identity {@code hashCode()}; equal hashes mean a missing {@code equals()}.
     */
    private static final class KeyCheck {
        private final Map<String, Set<Long>> hashesByFingerprint = new HashMap<>();
        private final Map<String, Integer> counts = new HashMap<>();
        private String keyClass;

        void add(HeapReader reader, Instance key, long hash) {
            if (key == null) {
                return;
            }
            String type = HeapReader.className(key);
            if (PackageCategorizer.categorize(type) != PackageCategory.APPLICATION) {
                return;
            }
            keyClass = type;
            String fingerprint = fingerprint(reader, key);
            hashesByFingerprint.computeIfAbsent(fingerprint, k -> new HashSet<>()).add(hash);
            counts.merge(fingerprint, 1, Integer::sum);
        }

        int duplicates() {
            int duplicates = 0;
            for (int count : counts.values()) {
                duplicates += count > 1 ? count - 1 : 0;
            }
            return duplicates;
        }

        boolean identityHash() {
            return counts.entrySet().stream()
                    .anyMatch(e -> e.getValue() > 1 && hashesByFingerprint.get(e.getKey()).size() > 1);
        }

        boolean broken() {
            return duplicates() > 0;
        }

        List<String> evidence() {
            if (!broken()) {
                return List.of();
            }
            String verdict = identityHash()
                    ? "у логически одинаковых ключей разные хэши — hashCode() не переопределён (используется"
                    + " identity hash)"
                    : "у одинаковых ключей одинаковый хэш, но они не равны — не переопределён equals()";
            String example = counts.entrySet().stream().filter(e -> e.getValue() > 1).findFirst()
                    .map(e -> e.getKey() + " × " + e.getValue()).orElse("");
            return List.of(
                    String.format("Ключи %s: %,d логических дубликатов в выборке из первых %,d записей; %s",
                            keyClass, duplicates(), KEY_SAMPLE, verdict),
                    "Пример ключа-дубликата: " + (example.length() > 300 ? example.substring(0, 300) + "…" : example));
        }

        private static String fingerprint(HeapReader reader, Instance key) {
            StringBuilder text = new StringBuilder(HeapReader.className(key)).append('{');
            for (Object value : key.getFieldValues()) {
                FieldValue field = (FieldValue) value;
                if (field.getField().isStatic()) {
                    continue;
                }
                text.append(field.getField().getName()).append('=');
                if (field instanceof ObjectFieldValue object) {
                    Instance referenced = object.getInstance();
                    String string = reader.string(referenced, 64);
                    text.append(referenced == null ? "null"
                            : string != null ? '"' + string + '"' : "#" + referenced.getInstanceId());
                } else {
                    text.append(field.getValue());
                }
                text.append(", ");
            }
            return text.append('}').toString();
        }
    }

    private static final String WHY = """
            Коллекция живёт столько же, сколько её владелец (см. «владелец» и путь до GC Root): static-поле, \
            синглтон, @ApplicationScoped-бин или долгоживущий сервис. В неё добавляют элементы, но ничто их не \
            удаляет: нет вытеснения по размеру или времени, нет remove() после обработки, нет очистки при выходе \
            пользователя или окончании задачи. GC не может освободить ни один элемент, пока коллекция достижима. \
            Поэтому её размер растёт вместе с числом запросов, пользователей или ключей.

            Очереди (LinkedBlockingQueue, ConcurrentLinkedQueue) растут, когда производитель быстрее потребителя: \
            неограниченная очередь «прячет» перегрузку, пока не закончится память.""";

    private static final String WHY_KEYS = """


            Дополнительно: в карте найдены логически одинаковые ключи, хранящиеся как РАЗНЫЕ записи. Класс ключа \
            не переопределяет equals()/hashCode() (или они зависят от изменяемых полей), поэтому put() с «тем же» \
            ключом не заменяет запись, а добавляет новую, а get() никогда не находит старую. Такая карта растёт \
            бесконечно при любом объёме данных — это гарантированная утечка.""";

    private static final String FIX = """
            // 1. Кэш — с ограничением размера и времени жизни (Caffeine)
            private static final Cache<OrderKey, Order> CACHE = Caffeine.newBuilder()
                    .maximumSize(10_000)
                    .expireAfterWrite(Duration.ofMinutes(10))
                    .build();

            // 2. Ключ карты — неизменяемый, с корректными equals()/hashCode(). Проще всего — record:
            public record OrderKey(long tenantId, String orderNumber) { }

            //    Для обычного класса — оба метода по одним и тем же неизменяемым полям:
            @Override public boolean equals(Object o) {
                return o instanceof OrderKey k && tenantId == k.tenantId && orderNumber.equals(k.orderNumber);
            }
            @Override public int hashCode() { return Objects.hash(tenantId, orderNumber); }

            // 3. Очередь — ограниченная, с явной реакцией на переполнение
            private final BlockingQueue<Event> queue = new LinkedBlockingQueue<>(10_000);
            if (!queue.offer(event, 100, TimeUnit.MILLISECONDS)) {
                metrics.counter("events.dropped").increment();   // backpressure вместо OutOfMemoryError
            }

            // 4. «Реестры» (подписчики, сессии, задачи) — удаляйте элемент там же, где он завершается:
            try { registry.put(id, task); task.run(); } finally { registry.remove(id); }""";
}
