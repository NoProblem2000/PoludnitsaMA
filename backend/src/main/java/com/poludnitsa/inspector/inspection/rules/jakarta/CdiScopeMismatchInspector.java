package com.poludnitsa.inspector.inspection.rules.jakarta;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.springframework.stereotype.Component;

/**
 * Scope mismatches in Weld (CDI of WildFly):
 * <ul>
 *   <li>{@code CreationalContextImpl.dependentInstances} that keep growing: {@code @Dependent} objects obtained
 *       with {@code Instance<T>.get()} by a long-living bean and never destroyed;</li>
 *   <li>collection fields of {@code @ApplicationScoped} / {@code @Singleton} beans (taken from Weld's application and
 *       singleton contexts) that grew large;</li>
 *   <li>long-living beans holding request objects directly instead of through a client proxy.</li>
 * </ul>
 */
@Component
public class CdiScopeMismatchInspector implements LeakInspectionRule {

    static final List<String> CREATIONAL_CONTEXTS = List.of(
            "org.jboss.weld.contexts.CreationalContextImpl", "org.jboss.weld.context.CreationalContextImpl");
    static final List<String> LONG_LIVED_CONTEXTS = List.of(
            "org.jboss.weld.contexts.unbound.ApplicationContextImpl",
            "org.jboss.weld.context.unbound.ApplicationContextImpl",
            "org.jboss.weld.contexts.unbound.SingletonContextImpl",
            "org.jboss.weld.context.unbound.SingletonContextImpl");
    static final List<String> SHORT_LIVED = List.of(
            "io.undertow.servlet.spec.HttpServletRequestImpl", "io.undertow.servlet.spec.HttpServletResponseImpl",
            "io.undertow.server.HttpServerExchange", "org.hibernate.internal.SessionImpl",
            "org.jboss.resteasy.specimpl.ResteasyUriInfo", "org.jboss.resteasy.specimpl.ResteasyHttpHeaders");

    @Override
    public String id() {
        return "cdi.scope-mismatch";
    }

    @Override
    public String title() {
        return "CDI/Weld: долгоживущие бины удерживают короткоживущие объекты";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JAKARTA_WILDFLY;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return CREATIONAL_CONTEXTS.stream().anyMatch(name -> !context.reader().classesNamed(name).isEmpty())
                || LONG_LIVED_CONTEXTS.stream().anyMatch(name -> !context.reader().classesNamed(name).isEmpty());
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        List<InspectionFindingDto> findings = new ArrayList<>();
        dependentInstances(context, findings);
        longLivedBeans(context, findings);
        return findings;
    }

    private void dependentInstances(InspectionContext context, List<InspectionFindingDto> findings) {
        HeapReader reader = context.reader();
        int warning = context.settings().cdiDependentInstancesWarning();
        for (String name : CREATIONAL_CONTEXTS) {
            for (Instance creational : HeapReader.instancesOf(reader.withSubclasses(name))) {
                if (context.outOfBudget()) {
                    return;
                }
                Instance dependents = HeapReader.objectField(creational, "dependentInstances");
                long count = reader.size(dependents);
                if (count < warning) {
                    continue;
                }
                String bean = ownerBean(reader, creational);
                Tally types = new Tally();
                reader.forEachElement(dependents, 2_000, element -> {
                    Instance instance = HeapReader.objectField(element, "instance");
                    types.add(HeapReader.className(instance != null ? instance : element), 0);
                    return true;
                });
                FindingBuilder finding = context.finding(this, "dependent:" + creational.getInstanceId())
                        .severity(count >= 10L * warning ? Severity.CRITICAL : Severity.WARNING)
                        .title(String.format("Бин %s накопил %,d @Dependent-экземпляров (Instance.get() без destroy)",
                                GcRootPathResolver.simpleName(bean), count))
                        .wasted(creational.getRetainedSize())
                        .affected(count)
                        .suspect(bean, null)
                        .pathOf(creational)
                        .evidence("CreationalContext бина " + bean + ": dependentInstances = " + count)
                        .evidence("Удерживается: " + Bytes.format(creational.getRetainedSize()));
                types.top(5).forEach(entry -> finding.evidence("Тип dependent-объектов: " + entry.format()));
                findings.add(finding.why(WHY_DEPENDENT).fix(FIX_DEPENDENT).build());
            }
        }
    }

    private void longLivedBeans(InspectionContext context, List<InspectionFindingDto> findings) {
        HeapReader reader = context.reader();
        Set<Long> seenBeans = new HashSet<>();
        for (String name : LONG_LIVED_CONTEXTS) {
            String scope = name.contains("Singleton") ? "@Singleton" : "@ApplicationScoped";
            for (Instance weldContext : HeapReader.instancesOf(reader.withSubclasses(name))) {
                Instance store = findMap(reader, weldContext);
                reader.forEachMapEntry(store, 100_000, (key, contextual, hash) -> {
                    Instance bean = HeapReader.objectField(contextual, "instance");
                    if (bean == null) {
                        bean = contextual;
                    }
                    if (bean != null && seenBeans.add(bean.getInstanceId())) {
                        inspectBean(context, scope, bean, findings);
                    }
                    return !context.outOfBudget();
                });
            }
        }
    }

    private void inspectBean(InspectionContext context, String scope, Instance bean,
                             List<InspectionFindingDto> findings) {
        String beanClass = HeapReader.className(bean);
        if (PackageCategorizer.categorize(beanClass) != PackageCategory.APPLICATION) {
            return;
        }
        HeapReader reader = context.reader();
        for (ObjectFieldValue field : HeapReader.objectFields(bean)) {
            Instance value = field.getInstance();
            String fieldName = field.getField().getName();
            String valueClass = HeapReader.className(value);
            if (HeapReader.isCollection(value)) {
                long size = reader.size(value);
                long retained = value.getRetainedSize();
                if (size < context.settings().cdiCollectionElementsWarning() && context.shareOfHeap(retained) < 0.01) {
                    continue;
                }
                Severity severity = context.severityByShare(retained, 0.01, 0.05);
                Tally elements = new Tally();
                reader.forEachElement(value, 1_000, element -> {
                    elements.add(HeapReader.className(element), 0);
                    return true;
                });
                FindingBuilder finding = context.finding(this, "collection:" + value.getInstanceId())
                        .severity(severity == Severity.INFO ? Severity.WARNING : severity)
                        .title(String.format("Коллекция %s.%s в %s-бине: %,d элементов, %s",
                                GcRootPathResolver.simpleName(beanClass), fieldName, scope, size,
                                Bytes.format(retained)))
                        .wasted(retained)
                        .affected(size)
                        .suspect(beanClass, fieldName)
                        .pathOf(value)
                        .evidence(String.format("Поле %s.%s (%s): %,d элементов, удерживает %s (%.1f%% кучи)",
                                beanClass, fieldName, valueClass, size, Bytes.format(retained),
                                100 * context.shareOfHeap(retained)));
                elements.top(5).forEach(entry -> finding.evidence("Элементы: " + entry.format()));
                findings.add(finding.why(WHY_COLLECTION.formatted(scope)).fix(FIX_COLLECTION).build());
            } else if (SHORT_LIVED.stream().anyMatch(type -> HeapReader.isInstanceOf(value, type))) {
                findings.add(context.finding(this, "short-lived:" + bean.getInstanceId() + ":" + fieldName)
                        .severity(Severity.WARNING)
                        .title(String.format("%s-бин %s держит %s в поле %s", scope,
                                GcRootPathResolver.simpleName(beanClass), GcRootPathResolver.simpleName(valueClass),
                                fieldName))
                        .wasted(value.getRetainedSize())
                        .affected(1)
                        .suspect(beanClass, fieldName)
                        .pathOf(value)
                        .evidence("Тип удерживаемого объекта: " + valueClass + " — живёт один запрос/транзакцию")
                        .why(WHY_SHORT_LIVED.formatted(scope))
                        .fix(FIX_SHORT_LIVED)
                        .build());
            }
        }
    }

    private static String ownerBean(HeapReader reader, Instance creational) {
        for (HeapReader.Referrer referrer : reader.referrers(creational, 16)) {
            if ("creationalContext".equals(referrer.field())) {
                Instance instance = HeapReader.objectField(referrer.holder(), "instance");
                if (instance != null) {
                    return HeapReader.className(instance);
                }
            }
        }
        HeapReader.Owner owner = reader.owner(creational);
        return owner == null ? "?" : owner.className();
    }

    /** Weld keeps the bean store map one or two fields deep ({@code beanStore.delegate}). */
    private static Instance findMap(HeapReader reader, Instance root) {
        Deque<Instance> queue = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        queue.add(root);
        depths.add(0);
        Set<Long> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Instance current = queue.poll();
            int depth = depths.poll();
            if (!seen.add(current.getInstanceId())) {
                continue;
            }
            if (current != root && HeapReader.isCollection(current) && reader.size(current) >= 0) {
                return current;
            }
            if (depth < 3) {
                for (ObjectFieldValue field : HeapReader.objectFields(current)) {
                    queue.add(field.getInstance());
                    depths.add(depth + 1);
                }
            }
        }
        return null;
    }

    private static final String WHY_DEPENDENT = """
            Weld обязан вызвать @PreDestroy у каждого @Dependent-объекта, когда уничтожается его владелец. \
            Поэтому каждый экземпляр, полученный через Instance<T>.get() (или CDI.current().select(...).get()), \
            запоминается в CreationalContext бина, который его запросил.

            Если владелец @ApplicationScoped или @Singleton, его CreationalContext живёт до остановки приложения. \
            Каждый вызов get() добавляет запись в dependentInstances, и она не удаляется никогда: \
            сборщик мусора не может освободить ни сами объекты, ни всё, что они держат. Утечка растёт \
            пропорционально числу запросов. Классический сценарий — «фабрика» обработчиков внутри синглтона.""";

    private static final String FIX_DEPENDENT = """
            @ApplicationScoped
            public class ReportService {

                @Inject
                Instance<ReportBuilder> builders;     // ReportBuilder — @Dependent

                public Report build(Request request) {
                    // Вариант 1 (CDI 2.0+): Handle уничтожает экземпляр и убирает его из CreationalContext
                    try (Instance.Handle<ReportBuilder> handle = builders.getHandle()) {
                        return handle.get().build(request);
                    }
                }

                public Report buildLegacy(Request request) {
                    // Вариант 2: явный destroy() после использования
                    ReportBuilder builder = builders.get();
                    try {
                        return builder.build(request);
                    } finally {
                        builders.destroy(builder);
                    }
                }
            }

            // Вариант 3: если у ReportBuilder нет состояния — сделайте его @ApplicationScoped и внедряйте напрямую,
            // а если состояние есть, но на один запрос — @RequestScoped (Weld сам уничтожит его в конце запроса).""";

    private static final String WHY_COLLECTION = """
            Бин с областью %s создаётся один раз и живёт до undeploy, а его поля — вместе с ним. Коллекция в таком \
            поле, в которую только добавляют (кэш без вытеснения, «реестр» подписчиков, журнал событий, \
            Map<sessionId, ...>), растёт с каждым запросом и никогда не уменьшается. \
            Пока бин жив, GC не может освободить ни одного элемента.""";

    private static final String FIX_COLLECTION = """
            @ApplicationScoped
            public class PriceCache {

                // Было: private final Map<String, Price> cache = new ConcurrentHashMap<>();   — растёт бесконечно

                // Стало: ограниченный размер и время жизни (Caffeine)
                private final Cache<String, Price> cache = Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .recordStats()
                        .build();

                public Price get(String sku) {
                    return cache.get(sku, this::loadPrice);
                }
            }

            // Без внешних библиотек — LinkedHashMap с вытеснением (под synchronized):
            private final Map<String, Price> lru = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Price> eldest) {
                    return size() > 10_000;
                }
            };

            // Данные «на запрос» или «на пользователя» держите в @RequestScoped / @SessionScoped-бинах,
            // а не в Map внутри @ApplicationScoped.""";

    private static final String WHY_SHORT_LIVED = """
            %s-бин живёт всё время работы приложения, а объект в его поле — один HTTP-запрос или одну транзакцию. \
            Если такой объект присвоен полю напрямую (а не через клиентский прокси CDI), бин держит его после \
            окончания запроса: request/response, Hibernate Session и т.п. не освобождаются, а при параллельных \
            запросах поле перезаписывается, и разные пользователи видят чужие данные.""";

    private static final String FIX_SHORT_LIVED = """
            @ApplicationScoped
            public class AuditService {

                // Плохо: private HttpServletRequest lastRequest;  — присвоено вручную в методе

                @Inject
                HttpServletRequest request;        // внедрённый прокси: каждый вызов идёт к текущему запросу

                @PersistenceContext
                EntityManager em;                  // прокси, привязанный к текущей транзакции

                public void audit(String action) {
                    String user = request.getRemoteUser();   // данные берём в момент вызова,
                    em.persist(new AuditEvent(user, action)); // ссылки на сам запрос не сохраняем
                }
            }""";
}
