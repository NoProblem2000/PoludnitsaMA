package com.poludnitsa.inspector.inspection.rules.hazelcast;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.inspection.FindingBuilder;
import com.poludnitsa.inspector.inspection.InspectionContext;
import com.poludnitsa.inspector.inspection.LeakInspectionRule;
import com.poludnitsa.inspector.inspection.Tally;
import java.util.ArrayList;
import java.util.List;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.PrimitiveArrayInstance;
import org.springframework.stereotype.Component;

/**
 * Memory held by serialized Hazelcast objects ({@code HeapData} with a {@code byte[] payload}): total retained size,
 * breakdown by decoded serializer / class, the objects that hold them, and Near Caches without expiry.
 */
@Component
public class HazelcastSerializedHeapDataInspector implements LeakInspectionRule {

    static final String HEAP_DATA = "com.hazelcast.internal.serialization.impl.HeapData";
    static final String NEAR_CACHE = "com.hazelcast.internal.nearcache.impl.DefaultNearCache";
    private static final int HEADER_BYTES = 512;
    private static final int HOLDER_SAMPLE = 20_000;

    @Override
    public String id() {
        return "hazelcast.serialized-heapdata";
    }

    @Override
    public String title() {
        return "Hazelcast: сериализованные данные (HeapData) в памяти клиента";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.HAZELCAST_CLIENT;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return !context.reader().classesNamed(HEAP_DATA).isEmpty();
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        HeapReader reader = context.reader();
        Tally types = new Tally();
        Tally payloads = new Tally();
        Tally holders = new Tally();
        long total = 0;
        long count = 0;
        Instance largest = null;
        int cap = context.settings().maxInstancesPerRule();
        for (Instance data : HeapReader.instancesOf(reader.withSubclasses(HEAP_DATA))) {
            if (count >= cap) {
                context.markTruncated();
                break;
            }
            if ((count & 1023) == 0 && context.outOfBudget()) {
                break;
            }
            count++;
            long retained = data.getRetainedSize();
            total += retained;
            if (largest == null || retained > largest.getRetainedSize()) {
                largest = data;
            }
            Instance payload = HeapReader.objectField(data, "payload");
            String type = payload instanceof PrimitiveArrayInstance bytes
                    ? HazelcastPayloadDecoder.describe(reader.bytes(bytes, HEADER_BYTES))
                    : "HeapData без payload";
            types.add(type, retained);
            payloads.add(type, payload == null ? 0 : payload.getSize());
            if (count <= HOLDER_SAMPLE) {
                for (HeapReader.Referrer referrer : reader.referrers(data, 2)) {
                    holders.add(referrer.describe(), retained);
                }
            }
        }

        List<InspectionFindingDto> findings = new ArrayList<>();
        List<String> nearCacheLines = new ArrayList<>();
        nearCaches(context, findings, nearCacheLines);
        if (total < context.settings().minReportedWaste().toBytes()) {
            return findings;
        }
        FindingBuilder finding = context.finding(this, "heapdata")
                .severity(context.severityByShare(total, 0.02, 0.10))
                .title(String.format("Сериализованные данные Hazelcast (HeapData) занимают %s (%.1f%% кучи)",
                        Bytes.format(total), 100 * context.shareOfHeap(total)))
                .wasted(total)
                .affected(count)
                .suspect(HEAP_DATA, "payload")
                .pathOf(largest)
                .evidence(String.format("Объектов HeapData: %,d, удерживают %s", count, Bytes.format(total)));
        types.top(10).forEach(entry -> finding.evidence("Тип: " + entry.format()));
        if (!holders.isEmpty()) {
            finding.evidence(String.format("Кто держит HeapData (по первым %,d объектам):",
                    Math.min(count, HOLDER_SAMPLE)));
            holders.top(6).forEach(entry -> finding.evidence("  ← " + entry.format()));
        }
        nearCacheLines.forEach(finding::evidence);
        findings.add(finding.why(WHY).fix(FIX).build());
        return findings;
    }

    private void nearCaches(InspectionContext context, List<InspectionFindingDto> findings, List<String> lines) {
        HeapReader reader = context.reader();
        for (Instance nearCache : HeapReader.instancesOf(reader.withSubclasses(NEAR_CACHE))) {
            String name = reader.string(HeapReader.objectField(nearCache, "name"), 200);
            Instance config = HeapReader.objectField(nearCache, "nearCacheConfig");
            Instance eviction = HeapReader.objectField(config, "evictionConfig");
            long ttl = HeapReader.longField(config, "timeToLiveSeconds", -1);
            long maxIdle = HeapReader.longField(config, "maxIdleSeconds", -1);
            long size = HeapReader.longField(eviction, "size", -1);
            String policy = reader.enumName(HeapReader.objectField(eviction, "evictionPolicy"));
            String format = reader.enumName(HeapReader.objectField(config, "inMemoryFormat"));
            long retained = nearCache.getRetainedSize();
            String line = String.format("NearCache '%s': %s, TTL=%s, maxIdle=%s, eviction=%s size=%s, формат %s",
                    name, Bytes.format(retained), seconds(ttl), seconds(maxIdle), policy,
                    size == Integer.MAX_VALUE ? "без лимита" : String.valueOf(size), format);
            lines.add(line);
            boolean noExpiry = ttl <= 0 && maxIdle <= 0;
            boolean noLimit = "NONE".equals(policy) || size >= 1_000_000 || size == Integer.MAX_VALUE;
            if ((noExpiry || noLimit) && context.shareOfHeap(retained) >= 0.01) {
                findings.add(context.finding(this, "nearcache:" + nearCache.getInstanceId())
                        .severity(noExpiry && noLimit ? context.severityByShare(retained, 0.0, 0.10)
                                : Severity.WARNING)
                        .title(String.format("NearCache '%s' без %s: %s", name,
                                noExpiry && noLimit ? "TTL и лимита размера" : noExpiry ? "TTL/maxIdle" : "лимита размера",
                                Bytes.format(retained)))
                        .wasted(retained)
                        .affected(1)
                        .suspect(NEAR_CACHE, "nearCacheConfig")
                        .pathOf(nearCache)
                        .evidence(line)
                        .why(WHY_NEAR_CACHE)
                        .fix(FIX_NEAR_CACHE)
                        .build());
            }
        }
    }

    private static String seconds(long value) {
        return value <= 0 ? "нет" : value + " с";
    }

    private static final String WHY = """
            Hazelcast хранит ключи и значения в сериализованном виде: объект HeapData с массивом byte[] payload. \
            В клиенте внутри микросервиса HeapData появляются в Near Cache (формат BINARY по умолчанию), в очередях \
            и буферах исходящих/входящих сообщений, в результатах getAll/values/entrySet, а у встроенного члена \
            кластера — ещё и в партициях.

            Сериализованное значение не видно в обычном списке классов: память числится за byte[], и кажется, что \
            «течёт byte[]». В фактах приведена расшифровка заголовков payload: тип сериализатора \
            (IdentifiedDataSerializable factoryId/classId, Portable, Java Serializable с именем класса), размер по \
            каждому типу и кто держит эти объекты.

            Типичные причины большого объёма:
              • Near Cache без TTL/maxIdle и без лимита размера: в нём оседает всё, что когда-либо читали;
              • Java Serializable вместо компактной сериализации: payload в 3–10 раз больше самого объекта;
              • map.values() / entrySet() / getAll() по большим картам — весь результат материализуется в клиенте;
              • медленный потребитель событий или топика: сообщения копятся в очереди клиента.""";

    private static final String FIX = """
            // 1. Ограничьте Near Cache по времени и размеру
            NearCacheConfig nearCache = new NearCacheConfig("orders")
                    .setInMemoryFormat(InMemoryFormat.OBJECT)        // или BINARY: экономнее, но десериализация на get
                    .setTimeToLiveSeconds(300)
                    .setMaxIdleSeconds(120)
                    .setInvalidateOnChange(true);
            nearCache.getEvictionConfig()
                    .setEvictionPolicy(EvictionPolicy.LRU)
                    .setMaxSizePolicy(MaxSizePolicy.ENTRY_COUNT)
                    .setSize(10_000);
            clientConfig.addNearCacheConfig(nearCache);

            // 2. Компактная сериализация вместо java.io.Serializable (Hazelcast 5.2+)
            clientConfig.getSerializationConfig().getCompactSerializationConfig()
                    .addSerializer(new OrderCompactSerializer());     // или IdentifiedDataSerializable

            // 3. Не выгружайте карту целиком — постранично, с предикатом и проекцией
            PagingPredicate<Long, Order> page = Predicates.pagingPredicate(Predicates.sql("status = 'NEW'"), 500);
            Collection<Order> firstPage = map.values(page);
            Collection<String> ids = map.project(Projections.singleAttribute("id"), Predicates.alwaysTrue());""";

    private static final String WHY_NEAR_CACHE = """
            Near Cache — локальная копия данных распределённой карты внутри микросервиса. Без TTL/maxIdle запись \
            живёт, пока её не инвалидирует кластер. При политике NONE или огромном лимите в кэш попадает всё, что \
            сервис когда-либо читал. Для «длинного хвоста» ключей (каталоги, история заказов) кэш растёт до \
            размера всей карты и занимает кучу микросервиса.""";

    private static final String FIX_NEAR_CACHE = """
            <!-- hazelcast-client.xml -->
            <near-cache name="orders">
                <in-memory-format>BINARY</in-memory-format>
                <time-to-live-seconds>300</time-to-live-seconds>
                <max-idle-seconds>120</max-idle-seconds>
                <invalidate-on-change>true</invalidate-on-change>
                <eviction eviction-policy="LRU" max-size-policy="ENTRY_COUNT" size="10000"/>
            </near-cache>""";
}
