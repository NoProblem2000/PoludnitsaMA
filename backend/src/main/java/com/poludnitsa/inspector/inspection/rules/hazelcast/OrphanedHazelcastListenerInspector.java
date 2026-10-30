package com.poludnitsa.inspector.inspection.rules.hazelcast;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.springframework.stereotype.Component;

/**
 * Listener registrations of the Hazelcast client ({@code ClientListenerServiceImpl.registrations}, 3.x
 * {@code AbstractClientListenerService.registrations}): too many of them, the same listener class registered over
 * and over, and listeners whose captured outer instance ({@code this$0}, lambda {@code arg$N}, {@code val$x}) keeps a
 * heavy object alive.
 */
@Component
public class OrphanedHazelcastListenerInspector implements LeakInspectionRule {

    static final List<String> REGISTRIES = List.of(
            "com.hazelcast.client.impl.spi.impl.listener.ClientListenerServiceImpl",
            "com.hazelcast.client.spi.impl.listener.AbstractClientListenerService");
    private static final int SEARCH_DEPTH = 6;
    private static final long HEAVY_CAPTURE_BYTES = 1L << 20;

    @Override
    public String id() {
        return "hazelcast.orphaned-listeners";
    }

    @Override
    public String title() {
        return "Hazelcast: забытые и тяжёлые слушатели";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.HAZELCAST_CLIENT;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return REGISTRIES.stream().anyMatch(name -> !context.reader().classesNamed(name).isEmpty());
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        HeapReader reader = context.reader();
        int warning = context.settings().listenersWarning();
        List<InspectionFindingDto> findings = new ArrayList<>();
        Set<Long> seenRegistries = new HashSet<>();
        for (String name : REGISTRIES) {
            for (Instance registry : HeapReader.instancesOf(reader.withSubclasses(name))) {
                if (!seenRegistries.add(registry.getInstanceId())) {
                    continue;
                }
                Instance registrations = HeapReader.objectField(registry, "registrations");
                long total = reader.size(registrations);
                Map<String, ListenerGroup> groups = new LinkedHashMap<>();
                reader.forEachMapEntry(registrations, 50_000, (id, registration, hash) -> {
                    Instance listener = findUserListener(registration);
                    String key = listener != null ? HeapReader.className(listener)
                            : "<обработчик " + HeapReader.className(registration) + ">";
                    groups.computeIfAbsent(key, ListenerGroup::new).add(listener);
                    return !context.outOfBudget();
                });
                if (total >= warning) {
                    findings.add(tooMany(context, registry, total, groups));
                }
                for (ListenerGroup group : groups.values()) {
                    heavyCapture(context, group).ifPresent(findings::add);
                }
            }
        }
        return findings;
    }

    private InspectionFindingDto tooMany(InspectionContext context, Instance registry, long total,
                                         Map<String, ListenerGroup> groups) {
        ListenerGroup biggest = groups.values().stream()
                .max((a, b) -> Integer.compare(a.count, b.count)).orElse(null);
        boolean repeated = biggest != null && biggest.count >= 50;
        FindingBuilder finding = context.finding(this, "registrations:" + registry.getInstanceId())
                .severity(repeated || total >= 10L * context.settings().listenersWarning()
                        ? Severity.CRITICAL : Severity.WARNING)
                .title(String.format("Зарегистрировано %,d слушателей Hazelcast-клиента%s", total,
                        repeated ? "; " + GcRootPathResolver.simpleName(biggest.type) + " — " + biggest.count + " раз"
                                : ""))
                .wasted(HeapReader.objectField(registry, "registrations") == null ? 0
                        : HeapReader.objectField(registry, "registrations").getRetainedSize())
                .affected(total)
                .suspect(biggest != null && biggest.sample != null ? biggest.type : HeapReader.className(registry),
                        null)
                .pathOf(biggest != null && biggest.sample != null ? biggest.sample : registry)
                .evidence("Реестр: " + HeapReader.className(registry) + ".registrations = " + total);
        groups.values().stream()
                .sorted((a, b) -> Integer.compare(b.count, a.count))
                .limit(8)
                .forEach(group -> finding.evidence(String.format("%s × %d", group.type, group.count)));
        return finding.why(WHY_MANY).fix(FIX).build();
    }

    private Optional<InspectionFindingDto> heavyCapture(InspectionContext context, ListenerGroup group) {
        if (group.sample == null) {
            return Optional.empty();
        }
        for (ObjectFieldValue field : HeapReader.objectFields(group.sample)) {
            String fieldName = field.getField().getName();
            if (!(fieldName.equals("this$0") || fieldName.startsWith("arg$") || fieldName.startsWith("val$"))) {
                continue;
            }
            Instance captured = field.getInstance();
            long retained = captured.getRetainedSize();
            if (retained < HEAVY_CAPTURE_BYTES) {
                continue;
            }
            String capturedClass = HeapReader.className(captured);
            return Optional.of(context.finding(this, "capture:" + group.type + ":" + fieldName)
                    .severity(group.count >= 50 ? Severity.CRITICAL : Severity.WARNING)
                    .title(String.format("Слушатель %s через %s удерживает %s (%s)",
                            GcRootPathResolver.simpleName(group.type), fieldName,
                            GcRootPathResolver.simpleName(capturedClass), Bytes.format(retained)))
                    .wasted(retained)
                    .affected(group.count)
                    .suspect(group.type, fieldName)
                    .pathOf(captured)
                    .evidence(String.format("Регистраций этого слушателя: %d", group.count))
                    .evidence(String.format("Поле %s → %s, удерживает %s", fieldName, capturedClass,
                            Bytes.format(retained)))
                    .why(WHY_CAPTURE)
                    .fix(FIX)
                    .build());
        }
        return Optional.empty();
    }

    /** First application object reachable from the registration (handler → adapter → user listener). */
    private static Instance findUserListener(Instance registration) {
        Deque<Instance> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(registration);
        depths.add(0);
        while (!queue.isEmpty()) {
            Instance current = queue.poll();
            int depth = depths.poll();
            if (current == null || !seen.add(current.getInstanceId())) {
                continue;
            }
            String currentType = HeapReader.className(current);
            if (PackageCategorizer.categorize(currentType) == PackageCategory.APPLICATION) {
                return current;
            }
            if (depth >= SEARCH_DEPTH) {
                continue;
            }
            // Adapters are kept in arrays (InternalMapListenerAdapter.listenerAdapters), so arrays are followed too.
            List<Instance> next = new ArrayList<>();
            if (currentType.endsWith("[]")) {
                next.addAll(HeapReader.elements(current, 32));
            } else {
                HeapReader.objectFields(current).forEach(field -> next.add(field.getInstance()));
            }
            for (Instance child : next) {
                String type = HeapReader.className(child);
                if (!type.startsWith("java.") || type.endsWith("[]")) {
                    queue.add(child);
                    depths.add(depth + 1);
                }
            }
        }
        return null;
    }

    private static final class ListenerGroup {
        final String type;
        int count;
        Instance sample;

        ListenerGroup(String type) {
            this.type = type;
        }

        void add(Instance listener) {
            count++;
            if (sample == null && listener != null) {
                sample = listener;
            }
        }
    }

    private static final String WHY_MANY = """
            Каждый вызов addEntryListener / addMessageListener / addItemListener / addMembershipListener \
            регистрирует обработчик в клиенте (ClientListenerService.registrations) и подписку на событие на \
            членах кластера. Обработчик живёт, пока не вызван removeXxxListener(registrationId) или пока не \
            остановлен клиент. Сборщик мусора его не освободит: на него ссылается реестр клиента.

            Когда слушатель регистрируют в коде, который выполняется многократно (в @RequestScoped/@Dependent-бине, \
            в методе обработки запроса, при каждом переподключении), регистрации копятся. Каждое событие \
            доставляется N раз, растут CPU, трафик и память клиента и кластера. Один и тот же класс слушателя, \
            зарегистрированный десятки и сотни раз, — почти всегда именно эта ошибка.""";

    private static final String WHY_CAPTURE = """
            Анонимный класс (поле this$0), нестатический внутренний класс или лямбда (arg$1, val$x) неявно хранит \
            ссылку на внешний объект. Пока слушатель зарегистрирован в Hazelcast, этот внешний объект — обычно \
            целый сервис с кэшами, EntityManager или контекст запроса — не может быть собран GC. Если таких \
            регистраций много или внешний объект — бин с коротким сроком жизни, каждая регистрация «замораживает» \
            его копию.""";

    private static final String FIX = """
            @ApplicationScoped
            public class OrderEventsSubscriber {

                @Inject
                HazelcastInstance hazelcast;

                private UUID registrationId;            // Hazelcast 4+/5 (в 3.x — String)

                @PostConstruct
                void subscribe() {
                    // Регистрируем ОДИН раз за жизнь приложения, а не в обработчике запроса
                    registrationId = hazelcast.<Long, Order>getMap("orders")
                            .addEntryListener(new OrderListener(cacheInvalidator), true);
                }

                @PreDestroy
                void unsubscribe() {
                    hazelcast.getMap("orders").removeEntryListener(registrationId);
                }

                // static: без скрытой ссылки this$0 на сервис; держит только то, что ему действительно нужно
                static final class OrderListener implements EntryUpdatedListener<Long, Order> {
                    private final CacheInvalidator invalidator;

                    OrderListener(CacheInvalidator invalidator) {
                        this.invalidator = invalidator;
                    }

                    @Override
                    public void entryUpdated(EntryEvent<Long, Order> event) {
                        invalidator.evict(event.getKey());
                    }
                }
            }""";
}
