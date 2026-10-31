package com.poludnitsa.inspector.inspection.rules.jvm;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.common.JvmTypeNames;
import com.poludnitsa.inspector.common.PackageCategorizer;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.inspection.InspectionContext;
import com.poludnitsa.inspector.inspection.LeakInspectionRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.springframework.stereotype.Component;

/**
 * Boxed primitives ({@code Integer}, {@code Long}, {@code Double}, ...) stored in bulk where primitives would do:
 *
 * <ul>
 *   <li>object arrays and the arrays behind lists ({@code ArrayList<Long>}, {@code Integer[]}),</li>
 *   <li>maps with boxed keys or values ({@code Map<Long, Order>}, {@code Map<String, Integer>}),</li>
 *   <li>fields of application classes with many instances ({@code Long id}, {@code Double price}).</li>
 * </ul>
 *
 * Each boxed value is an object of its own: a 12-byte header plus the value padded to 16 or 24 bytes, and a
 * reference to it. Values in the JDK caches ({@code Integer.valueOf} for -128..127, {@code Boolean.TRUE/FALSE}) are
 * shared and only cost the reference. The saving per holder is what {@code long[]}, {@code int[]}, primitive fields
 * or specialised primitive collections would give back, including the map entry objects those collections do not
 * need.
 */
@Component
public class BoxedPrimitivesOverheadInspector implements LeakInspectionRule {

    /** box class -> size of the primitive */
    static final Map<String, Integer> BOXES = Map.of(
            "java.lang.Integer", 4, "java.lang.Long", 8, "java.lang.Double", 8, "java.lang.Float", 4,
            "java.lang.Short", 2, "java.lang.Byte", 1, "java.lang.Character", 2, "java.lang.Boolean", 1);
    private static final double DOMINANT_SHARE = 0.8;
    private static final int MIN_ARRAY_LENGTH = 32;
    private static final int FULL_SCAN_LENGTH = 200_000;
    private static final int ARRAY_SAMPLE = 64;
    private static final int MIN_MAP_SIZE = 1_000;
    private static final int MAP_SAMPLE = 64;
    private static final int MIN_CLASS_INSTANCES = 10_000;
    private static final int FIELD_SAMPLE = 200;

    @Override
    public String id() {
        return "jvm.boxed-primitives";
    }

    @Override
    public String title() {
        return "Упакованные примитивы (Integer, Long, Double) в массивах, коллекциях и полях";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JVM_CORE;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return context.reader().classesNamed("java.lang.Long").stream().anyMatch(c -> c.getInstancesCount() > 0)
                || context.reader().classesNamed("java.lang.Integer").stream().anyMatch(c -> c.getInstancesCount() > 0)
                || context.reader().classesNamed("java.lang.Double").stream().anyMatch(c -> c.getInstancesCount() > 0);
    }

    /** One place that holds boxed values: an array owner, a map owner or a field. */
    private static final class Holder {
        final String key;
        final String kind;
        final String where;
        final String ownerClass;
        final String field;
        final String box;
        long values;
        long boxedObjects;
        long saving;
        Instance sample;

        Holder(String key, String kind, String where, String ownerClass, String field, String box) {
            this.key = key;
            this.kind = kind;
            this.where = where;
            this.ownerClass = ownerClass;
            this.field = field;
            this.box = box;
        }
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        Map<String, Holder> holders = new HashMap<>();
        scanArrays(context, holders);
        scanMaps(context, holders);
        scanFields(context, holders);

        long threshold = context.settings().minReportedWaste().toBytes();
        List<Holder> reported = new ArrayList<>(holders.values().stream()
                .filter(h -> h.saving >= threshold && !jdkOwned(h.ownerClass)).toList());
        reported.sort((a, b) -> Long.compare(b.saving, a.saving));
        BoxTotals totals = totals(context);
        List<InspectionFindingDto> findings = new ArrayList<>();
        for (Holder holder : reported.subList(0, Math.min(reported.size(), context.settings().maxFindingsPerRule()))) {
            findings.add(describe(context, holder, totals));
        }
        return findings;
    }

    // ---------------------------------------------------------------- arrays and lists

    private void scanArrays(InspectionContext context, Map<String, Holder> holders) {
        HeapReader reader = context.reader();
        Map<Long, HeapReader.Owner> owners = new HashMap<>();
        int cap = context.settings().maxInstancesPerRule();
        int scanned = 0;
        List<String> arrayClasses = new ArrayList<>(List.of("java.lang.Object[]"));
        BOXES.keySet().forEach(box -> arrayClasses.add(box + "[]"));
        outer:
        for (String arrayClass : arrayClasses) {
            for (JavaClass javaClass : reader.classesNamed(arrayClass)) {
                Iterator<?> instances = javaClass.getInstancesIterator();
                while (instances.hasNext()) {
                    Instance array = (Instance) instances.next();
                    if (++scanned > cap) {
                        context.markTruncated();
                        break outer;
                    }
                    if ((scanned & 1023) == 0 && context.outOfBudget()) {
                        break outer;
                    }
                    int length = HeapReader.length(array);
                    if (length < MIN_ARRAY_LENGTH) {
                        continue;
                    }
                    Census census = census(reader, HeapReader.elements(array, ARRAY_SAMPLE));
                    if (census == null) {
                        continue;
                    }
                    // the sample decided the type; count the whole array (or extrapolate very long ones)
                    Census full = length <= FULL_SCAN_LENGTH
                            ? census(reader, HeapReader.elements(array, length)) : census.scaled(length, ARRAY_SAMPLE);
                    if (full == null || !full.box.equals(census.box)) {
                        continue;
                    }
                    long perSlot = Math.max(4, array.getSize() / length);
                    long saving = full.saving(perSlot);
                    if (saving <= 0) {
                        continue;
                    }
                    HeapReader.Owner owner = owners.computeIfAbsent(array.getInstanceId(), id -> reader.owner(array));
                    String ownerClass = owner == null ? "?" : owner.className();
                    String field = owner == null ? null : owner.field();
                    String where = owner == null ? JvmTypeNames.toJavaName(javaClass.getName()) : owner.describe();
                    Holder holder = holders.computeIfAbsent("array#" + where + "#" + full.box,
                            k -> new Holder(k, "массив / список", where, ownerClass, field, full.box));
                    holder.values += full.values;
                    holder.boxedObjects += full.distinctObjects;
                    holder.saving += saving;
                    if (holder.sample == null) {
                        holder.sample = array;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- maps

    private void scanMaps(InspectionContext context, Map<String, Holder> holders) {
        HeapReader reader = context.reader();
        int cap = context.settings().maxInstancesPerRule();
        for (String mapClass : List.of("java.util.HashMap", "java.util.concurrent.ConcurrentHashMap",
                "java.util.TreeMap", "java.util.Hashtable")) {
            int scanned = 0;
            for (Instance map : HeapReader.instancesOf(reader.withSubclasses(mapClass))) {
                if (++scanned > cap || ((scanned & 1023) == 0 && context.outOfBudget())) {
                    break;
                }
                long size = reader.size(map);
                if (size < MIN_MAP_SIZE) {
                    continue;
                }
                List<Instance> keys = new ArrayList<>();
                List<Instance> values = new ArrayList<>();
                reader.forEachMapEntry(map, MAP_SAMPLE, (key, value, hash) -> {
                    if (key != null) {
                        keys.add(key);
                    }
                    if (value != null) {
                        values.add(value);
                    }
                    return true;
                });
                Census keyCensus = census(reader, keys);
                Census valueCensus = census(reader, values);
                if (keyCensus == null && valueCensus == null) {
                    continue;
                }
                // a specialised primitive map stores keys and values in arrays: no boxes, no entry objects
                long perEntry;
                String box;
                if (keyCensus != null && valueCensus != null) {
                    perEntry = keyCensus.perValueSaving(4) + valueCensus.perValueSaving(4);
                    box = keyCensus.box + " → " + valueCensus.box;
                } else if (keyCensus != null) {
                    perEntry = keyCensus.perValueSaving(4);
                    box = keyCensus.box + " (ключ)";
                } else {
                    perEntry = valueCensus.perValueSaving(4);
                    box = valueCensus.box + " (значение)";
                }
                Instance table = HeapReader.objectField(map, "table");
                Instance firstNode = table == null ? null : HeapReader.elements(table, 64).stream().findFirst().orElse(null);
                long nodeSize = firstNode == null ? 32 : firstNode.getSize();
                long saving = size * (perEntry + nodeSize);
                HeapReader.Owner owner = reader.owner(map);
                String ownerClass = owner == null ? "?" : owner.className();
                String where = owner == null ? GcRootPathResolver.simpleName(HeapReader.className(map)) : owner.describe();
                String mapType = GcRootPathResolver.simpleName(HeapReader.className(map));
                Holder holder = holders.computeIfAbsent("map#" + where + "#" + box,
                        k -> new Holder(k, mapType, where, ownerClass, owner == null ? null : owner.field(), box));
                holder.values += size;
                holder.boxedObjects += size;
                holder.saving += saving;
                if (holder.sample == null) {
                    holder.sample = map;
                }
            }
        }
    }

    // ---------------------------------------------------------------- fields of numerous application classes

    private void scanFields(InspectionContext context, Map<String, Holder> holders) {
        HeapReader reader = context.reader();
        for (Object element : context.heap().getAllClasses()) {
            if (context.outOfBudget()) {
                return;
            }
            JavaClass javaClass = (JavaClass) element;
            int count = javaClass.getInstancesCount();
            String className = JvmTypeNames.toJavaName(javaClass.getName());
            if (count < MIN_CLASS_INSTANCES || javaClass.isArray() || jdkOwned(className)) {
                continue;
            }
            Map<String, List<Instance>> byField = new HashMap<>();
            Iterator<?> instances = javaClass.getInstancesIterator();
            long stride = Math.max(1, count / FIELD_SAMPLE);
            long index = 0;
            int sampled = 0;
            while (instances.hasNext() && sampled < FIELD_SAMPLE) {
                Instance instance = (Instance) instances.next();
                if (index++ % stride != 0) {
                    continue;
                }
                sampled++;
                for (ObjectFieldValue field : HeapReader.objectFields(instance)) {
                    Instance value = field.getInstance();
                    if (value != null && BOXES.containsKey(HeapReader.className(value))) {
                        byField.computeIfAbsent(field.getField().getName(), f -> new ArrayList<>()).add(value);
                    }
                }
            }
            for (Map.Entry<String, List<Instance>> field : byField.entrySet()) {
                if (field.getValue().size() < sampled * DOMINANT_SHARE) {
                    continue;
                }
                Census census = census(reader, field.getValue());
                if (census == null) {
                    continue;
                }
                // a primitive field replaces the reference: only the box itself (minus a wider primitive) is saved
                long perValue = census.perValueSaving(4);
                double holding = (double) field.getValue().size() / sampled;
                long estimated = (long) (count * holding);
                long saving = (long) (estimated * perValue);
                if (saving <= 0) {
                    continue;
                }
                Holder holder = holders.computeIfAbsent("field#" + className + "." + field.getKey(),
                        k -> new Holder(k, "поле", GcRootPathResolver.simpleName(className) + "." + field.getKey(),
                                className, field.getKey(), census.box));
                holder.values += estimated;
                holder.boxedObjects += (long) (estimated * census.nonCachedShare());
                holder.saving += saving;
                if (holder.sample == null) {
                    holder.sample = field.getValue().get(0);
                }
            }
        }
    }

    // ---------------------------------------------------------------- census of boxed values

    /** Boxed values of one dominant type among some elements. */
    private record Census(String box, long values, long distinctObjects, long nonCached, long boxBytes) {

        double nonCachedShare() {
            return values == 0 ? 0 : (double) nonCached / values;
        }

        /** Saved per value: the box object (non-cached ones), plus the reference, minus the primitive. */
        long perValueSaving(long referenceSize) {
            long primitive = BOXES.get(box);
            double boxPerValue = values == 0 ? 0 : (double) boxBytes / values;
            return Math.max(0, Math.round(boxPerValue + referenceSize - primitive));
        }

        long saving(long referenceSize) {
            return values * perValueSaving(referenceSize);
        }

        Census scaled(long length, int sampledSlots) {
            double factor = (double) length / Math.max(1, sampledSlots);
            return new Census(box, (long) (values * factor), (long) (distinctObjects * factor),
                    (long) (nonCached * factor), (long) (boxBytes * factor));
        }
    }

    /** {@code null} unless at least {@value #DOMINANT_SHARE} of the elements are boxes of one type. */
    private static Census census(HeapReader reader, List<Instance> elements) {
        if (elements.isEmpty()) {
            return null;
        }
        Map<String, Integer> byBox = new HashMap<>();
        for (Instance element : elements) {
            String type = HeapReader.className(element);
            if (BOXES.containsKey(type)) {
                byBox.merge(type, 1, Integer::sum);
            }
        }
        Map.Entry<String, Integer> dominant = byBox.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
        if (dominant == null || dominant.getValue() < elements.size() * DOMINANT_SHARE) {
            return null;
        }
        String box = dominant.getKey();
        long values = 0;
        long nonCached = 0;
        long boxBytes = 0;
        Set<Long> distinct = new HashSet<>();
        for (Instance element : elements) {
            if (!box.equals(HeapReader.className(element))) {
                continue;
            }
            values++;
            if (!cached(box, element)) {
                nonCached++;
                if (distinct.add(element.getInstanceId())) {
                    boxBytes += element.getSize();
                }
            }
        }
        return new Census(box, values, distinct.size(), nonCached, boxBytes);
    }

    /** Values every {@code valueOf} shares: -128..127 for the integral boxes, both Booleans. */
    private static boolean cached(String box, Instance value) {
        return switch (box) {
            case "java.lang.Boolean" -> true;
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte" -> {
                long v = HeapReader.longField(value, "value", Long.MIN_VALUE);
                yield v >= -128 && v <= 127;
            }
            case "java.lang.Character" -> HeapReader.longField(value, "value", Long.MAX_VALUE) <= 127;
            default -> false;
        };
    }

    private record BoxTotals(long instances, long bytes) {
    }

    private static BoxTotals totals(InspectionContext context) {
        long instances = 0;
        long bytes = 0;
        for (String box : BOXES.keySet()) {
            for (JavaClass javaClass : context.reader().classesNamed(box)) {
                instances += javaClass.getInstancesCount();
                bytes += javaClass.getAllInstancesSize();
            }
        }
        return new BoxTotals(instances, bytes);
    }

    private static boolean jdkOwned(String className) {
        PackageCategory category = PackageCategorizer.categorize(className);
        return category == PackageCategory.JDK || category == PackageCategory.PRIMITIVE_OR_ARRAY;
    }

    private InspectionFindingDto describe(InspectionContext context, Holder holder, BoxTotals totals) {
        return context.finding(this, holder.key)
                .severity(context.severityByShare(holder.saving, 0.02, 0.10))
                .title(String.format("%s в %s (%s): %,d значений, можно сэкономить %s", shortBox(holder.box),
                        holder.where, holder.kind, holder.values, Bytes.format(holder.saving)))
                .wasted(holder.saving)
                .affected(holder.values)
                .suspect(holder.ownerClass, holder.field)
                .pathOf(holder.sample)
                .evidence(String.format("Тип: %s; где: %s (%s)", shortBox(holder.box), holder.where, holder.kind))
                .evidence(String.format("Значений: %,d, отдельных объектов-обёрток вне кэша valueOf: %,d",
                        holder.values, holder.boxedObjects))
                .evidence(String.format("Экономия при переходе на примитивы: %s", Bytes.format(holder.saving)))
                .evidence(String.format("Всего обёрток в куче: %,d объектов, %s", totals.instances(),
                        Bytes.format(totals.bytes())))
                .why(WHY)
                .fix(FIX)
                .build();
    }

    private static String shortBox(String box) {
        return box.replace("java.lang.", "");
    }

    private static final String WHY = """
            Integer, Long и Double — это объекты: заголовок 12 байт плюс значение, с выравниванием 16 или 24 байта, \
            и ещё ссылка на объект (4 байта). Long в List<Long> занимает 24 + 4 = 28 байт вместо 8 в long[]; запись \
            Map<Long, Order> — объект узла HashMap$Node (32 байта) и объект Long (24 байта) сверх самих данных.

            Кэш Integer.valueOf / Long.valueOf покрывает только -128..127: идентификаторы, суммы, временные метки \
            почти всегда за его пределами, и каждое значение — отдельный объект. Миллионы таких объектов:
              • раздувают old gen и время маркировки GC;
              • рассыпаны по куче — хуже работает кэш процессора;
              • в Hibernate-сущностях и DTO умножаются на число загруженных строк.

            Это не утечка, а постоянный перерасход памяти, который виден в каждом дампе и растёт с объёмом данных.""";

    private static final String FIX = """
            // 1. Поля сущностей и DTO — примитивы, если null не нужен
            private long id;                 // вместо Long id
            private double price;            // вместо Double price
            // nullable в БД? OptionalLong / отдельный флаг, либо оставить обёртку только там

            // 2. Списки чисел — массивы или специализированные коллекции
            long[] ids = orders.stream().mapToLong(Order::getId).toArray();

            // Eclipse Collections (или fastutil / HPPC):
            MutableLongList ids = new LongArrayList(expected);
            ids.add(order.getId());

            // 3. Карты с числовыми ключами — без HashMap$Node и Long на каждую запись
            MutableLongObjectMap<Order> byId = new LongObjectHashMap<>(expected);
            byId.put(order.getId(), order);

            MutableObjectIntMap<String> counters = new ObjectIntHashMap<>();
            counters.addToValue(key, 1);     // вместо Map<String, Integer>.merge(key, 1, Integer::sum)

            // 4. Агрегаты — примитивные стримы, без промежуточных List<Long>
            long total = items.stream().mapToLong(Item::getAmount).sum();""";
}
