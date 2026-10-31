package com.poludnitsa.inspector.inspection.rules.jvm;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.inspection.InspectionContext;
import com.poludnitsa.inspector.inspection.LeakInspectionRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.netbeans.lib.profiler.heap.Instance;
import org.springframework.stereotype.Component;

/**
 * Collections that hold memory for elements they do not have: {@code ArrayList}, {@code Vector}, {@code HashMap},
 * {@code HashSet} and {@code ConcurrentHashMap} that are empty but have allocated their backing array, or whose
 * {@code size / capacity} is below {@value #SPARSE_RATIO} at a capacity of at least {@value #MIN_SPARSE_CAPACITY}.
 *
 * <p>Collections are grouped by the field that holds them ({@code Order.attributes}), because the fix is in that
 * field's initialisation: one empty {@code HashMap} costs ~100 bytes, a million of them in a million orders are
 * 100 MB. The saving is what lazy initialisation ({@code Collections.emptyMap()} until the first put), a right-sized
 * constructor or {@code trimToSize()} would give back. Lists created with {@code new ArrayList<>()} share an empty
 * array until the first add and cost nothing here; neither do collections owned by the JDK itself.
 */
@Component
public class EmptyAndSparseCollectionsInspector implements LeakInspectionRule {

    static final double SPARSE_RATIO = 0.15;
    static final int MIN_SPARSE_CAPACITY = 64;
    /** HashMap resizes at 75 % fill: the right capacity for n entries is n / 0.75 */
    private static final double LOAD_FACTOR = 0.75;
    private static final int DEFAULT_MAP_CAPACITY = 16;

    private enum Type {
        LIST("elementData", "size", "ArrayList"),
        VECTOR("elementData", "elementCount", "Vector"),
        MAP("table", "size", "HashMap"),
        CONCURRENT_MAP("table", null, "ConcurrentHashMap");

        final String array;
        final String sizeField;
        final String label;

        Type(String array, String sizeField, String label) {
            this.array = array;
            this.sizeField = sizeField;
            this.label = label;
        }
    }

    private static final Map<String, Type> TYPES = Map.of(
            "java.util.ArrayList", Type.LIST,
            "java.util.Vector", Type.VECTOR,
            "java.util.HashMap", Type.MAP,
            "java.util.concurrent.ConcurrentHashMap", Type.CONCURRENT_MAP);

    @Override
    public String id() {
        return "jvm.empty-sparse-collections";
    }

    @Override
    public String title() {
        return "Пустые и разреженные коллекции";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JVM_CORE;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return true;
    }

    /** One owner field: {@code com.acme.Order.attributes} holding {@code HashMap}s. */
    private static final class Group {
        final String ownerClass;
        final String field;
        final String collectionLabel;
        long collections;
        long empty;
        long sparse;
        long wasted;
        long capacitySum;
        long sizeSum;
        Instance sample;
        long sampleWaste = -1;

        Group(String ownerClass, String field, String collectionLabel) {
            this.ownerClass = ownerClass;
            this.field = field;
            this.collectionLabel = collectionLabel;
        }
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        HeapReader reader = context.reader();
        int cap = context.settings().maxInstancesPerRule();
        Map<String, Group> groups = new HashMap<>();
        long scannedTotal = 0;
        outer:
        for (Map.Entry<String, Type> type : TYPES.entrySet()) {
            int scanned = 0;
            for (Instance collection : HeapReader.instancesOf(reader.withSubclasses(type.getKey()))) {
                if (++scanned > cap) {
                    context.markTruncated();
                    break;
                }
                if ((scanned & 1023) == 0 && context.outOfBudget()) {
                    break outer;
                }
                scannedTotal++;
                Waste waste = waste(reader, collection, type.getValue());
                if (waste == null) {
                    continue;
                }
                Holder holder = holder(reader, collection, type.getValue());
                if (holder == null || PackageCategorizer.categorize(holder.ownerClass) == PackageCategory.JDK) {
                    continue;
                }
                Group group = groups.computeIfAbsent(holder.key(),
                        k -> new Group(holder.ownerClass, holder.field, holder.label));
                group.collections++;
                group.wasted += waste.bytes;
                group.capacitySum += waste.capacity;
                group.sizeSum += Math.max(0, waste.size);
                if (waste.size == 0) {
                    group.empty++;
                } else {
                    group.sparse++;
                }
                if (waste.bytes > group.sampleWaste) {
                    group.sample = holder.collection;
                    group.sampleWaste = waste.bytes;
                }
            }
        }

        long threshold = context.settings().minReportedWaste().toBytes();
        List<Group> reported = new ArrayList<>(groups.values().stream().filter(g -> g.wasted >= threshold).toList());
        reported.sort((a, b) -> Long.compare(b.wasted, a.wasted));
        List<InspectionFindingDto> findings = new ArrayList<>();
        long totalWaste = reported.stream().mapToLong(g -> g.wasted).sum();
        for (Group group : reported.subList(0, Math.min(reported.size(), context.settings().maxFindingsPerRule()))) {
            if (context.outOfBudget()) {
                break;
            }
            findings.add(describe(context, group, scannedTotal, totalWaste));
        }
        return findings;
    }

    private record Waste(long bytes, long size, long capacity) {
    }

    /** What an empty or sparse collection could give back, {@code null} when it is fine. */
    private static Waste waste(HeapReader reader, Instance collection, Type type) {
        Instance array = HeapReader.objectField(collection, type.array);
        int capacity = HeapReader.length(array);
        if (capacity <= 0) {
            // lazily allocated or the shared empty array: nothing to save
            return null;
        }
        long size = type.sizeField != null ? HeapReader.longField(collection, type.sizeField, -1) : reader.size(collection);
        if (size < 0) {
            return null;
        }
        long arrayBytes = array.getSize();
        if (size == 0) {
            // lazy initialisation saves the array and the collection object itself
            return new Waste(arrayBytes + collection.getSize(), 0, capacity);
        }
        if (capacity < MIN_SPARSE_CAPACITY || (double) size / capacity >= SPARSE_RATIO) {
            return null;
        }
        long needed = type == Type.LIST || type == Type.VECTOR
                ? size
                : Math.max(DEFAULT_MAP_CAPACITY, Integer.highestOneBit((int) Math.ceil(size / LOAD_FACTOR) * 2 - 1));
        if (needed >= capacity) {
            return null;
        }
        long perSlot = arrayBytes / capacity;
        return new Waste((capacity - needed) * perSlot, size, capacity);
    }

    private record Holder(Instance collection, String ownerClass, String field, String label) {
        String key() {
            return ownerClass + "#" + field + "#" + label;
        }
    }

    /**
     * The field that holds the collection. A HashMap inside a HashSet is reported as the set, under the set's owner;
     * collections in arrays or other collections are reported under the element container.
     */
    private static Holder holder(HeapReader reader, Instance collection, Type type) {
        List<HeapReader.Referrer> referrers = reader.referrers(collection, 1);
        if (referrers.isEmpty()) {
            return null;
        }
        HeapReader.Referrer referrer = referrers.get(0);
        Instance reported = collection;
        String label = type.label;
        if (type == Type.MAP && "map".equals(referrer.field())
                && HeapReader.isInstanceOf(referrer.holder(), "java.util.HashSet")) {
            reported = referrer.holder();
            label = "HashSet";
            List<HeapReader.Referrer> up = reader.referrers(reported, 1);
            if (up.isEmpty()) {
                return null;
            }
            referrer = up.get(0);
        }
        if (referrer.index() >= 0) {
            return new Holder(reported, referrer.holderClass(), "[]", label);
        }
        return new Holder(reported, referrer.holderClass(),
                referrer.staticField() ? "static " + referrer.field() : referrer.field(), label);
    }

    private InspectionFindingDto describe(InspectionContext context, Group group, long scanned, long totalWaste) {
        String where = group.field.equals("[]")
                ? "элементах " + GcRootPathResolver.simpleName(group.ownerClass)
                : GcRootPathResolver.simpleName(group.ownerClass) + "." + group.field;
        double fill = group.capacitySum == 0 ? 0 : 100.0 * group.sizeSum / group.capacitySum;
        return context.finding(this, group.ownerClass + "#" + group.field + "#" + group.collectionLabel)
                .severity(context.severityByShare(group.wasted, 0.02, 0.10))
                .title(String.format("%s в %s: %,d пустых или разреженных, можно сэкономить %s",
                        group.collectionLabel, where, group.collections, Bytes.format(group.wasted)))
                .wasted(group.wasted)
                .affected(group.collections)
                .suspect(group.ownerClass, group.field.equals("[]") ? null : group.field)
                .pathOf(group.sample)
                .evidence(String.format("Коллекций %s в %s: %,d — пустых %,d, заполненных меньше %.0f%%: %,d",
                        group.collectionLabel, group.ownerClass + (group.field.equals("[]") ? "[]" : "." + group.field),
                        group.collections, group.empty, SPARSE_RATIO * 100, group.sparse))
                .evidence(String.format("Средняя заполненность: %.1f%% (%,d элементов на %,d слотов)", fill,
                        group.sizeSum, group.capacitySum))
                .evidence(String.format("Экономия: %s в этой группе, %s по всем группам; просмотрено %,d коллекций",
                        Bytes.format(group.wasted), Bytes.format(totalWaste), scanned))
                .why(WHY)
                .fix(FIX)
                .build();
    }

    private static final String WHY = """
            Коллекция резервирует внутренний массив (elementData у ArrayList, table у HashMap) заранее: \
            new ArrayList<>(100) сразу выделяет 100 слотов, new HashMap<>() — таблицу из 16 после первого put, \
            а после clear() таблица остаётся прежнего размера. Если такая коллекция живёт в поле каждого объекта \
            (каждого заказа, сессии, DTO), пустые и почти пустые массивы умножаются на число объектов.

            Типичные причины:
              • поле инициализируется при объявлении (private Map<String, Object> attrs = new HashMap<>()), \
            хотя у большинства объектов оно так и остаётся пустым;
              • ёмкость задана «с запасом» (new ArrayList<>(1000)) для коллекции, где обычно 3 элемента;
              • коллекцию переиспользуют через clear(): элементов нет, а массив на пике — остался;
              • списки собраны и больше не меняются, но не обрезаны (trimToSize()).

            Это не утечка — память не растёт сама по себе, — но это постоянный налог на кучу и на GC: больше \
            объектов в old gen, дольше маркировка.""";

    private static final String FIX = """
            // 1. Ленивая инициализация: пустое поле ничего не стоит
            private Map<String, Object> attributes = Collections.emptyMap();

            public void setAttribute(String key, Object value) {
                if (attributes.isEmpty()) {
                    attributes = new HashMap<>(4);            // создаём при первом использовании
                }
                attributes.put(key, value);
            }

            // 2. Размер по реальным данным, а не «с запасом»
            List<Line> lines = new ArrayList<>(source.size());
            Map<Long, Item> byId = HashMap.newHashMap(expected);  // Java 19+: ёмкость под expected элементов

            // 3. Неизменяемые после сборки коллекции — компактные копии
            this.tags = List.copyOf(tags);                    // без пустых слотов
            this.index = Map.copyOf(index);

            // 4. Переиспользуемый буфер, выросший на пике, — заменить, а не clear()
            if (buffer.size() > 10_000) {
                buffer = new ArrayList<>();
            } else {
                buffer.clear();
            }

            // ArrayList, который уже не будет расти:
            list.trimToSize();""";
}
