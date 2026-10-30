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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.netbeans.lib.profiler.heap.Instance;
import org.springframework.stereotype.Component;

/**
 * Values stuck in {@code ThreadLocal}s of WildFly pool threads ({@code default task-*}, {@code XNIO-*},
 * {@code ejb-client-*}, ...). Walks {@code Thread.threadLocals / inheritableThreadLocals -> ThreadLocalMap.table ->
 * Entry(referent = ThreadLocal, value)} and groups the values by ThreadLocal and value type across threads.
 */
@Component
public class ThreadLocalLeakInspector implements LeakInspectionRule {

    private static final int VALUE_PEEK = 20;

    @Override
    public String id() {
        return "wildfly.threadlocal-pool-leak";
    }

    @Override
    public String title() {
        return "ThreadLocal в потоках пула WildFly";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JAKARTA_WILDFLY;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return !context.reader().classesNamed("java.lang.Thread").isEmpty();
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        HeapReader reader = context.reader();
        List<String> prefixes = context.settings().poolThreadPrefixes();
        Map<String, Group> groups = new LinkedHashMap<>();
        int poolThreads = 0;
        for (Instance thread : HeapReader.instancesOf(reader.withSubclasses("java.lang.Thread"))) {
            if (context.outOfBudget()) {
                break;
            }
            String name = reader.string(HeapReader.objectField(thread, "name"), 200);
            if (name == null || prefixes.stream().noneMatch(name::startsWith)) {
                continue;
            }
            poolThreads++;
            for (String mapField : List.of("threadLocals", "inheritableThreadLocals")) {
                Instance map = HeapReader.objectField(thread, mapField);
                for (Instance entry : HeapReader.elements(HeapReader.objectField(map, "table"), Integer.MAX_VALUE)) {
                    Instance value = HeapReader.objectField(entry, "value");
                    if (value == null) {
                        continue;
                    }
                    Instance key = HeapReader.objectField(entry, "referent");
                    ValueKind kind = describeValue(reader, value);
                    String local = key == null ? STALE : threadLocalLabel(reader, key);
                    Group group = groups.computeIfAbsent(local + "|" + kind.label(),
                            k -> new Group(local, kind, key == null));
                    group.add(name, value);
                }
            }
        }

        long threshold = context.settings().threadLocalMinBytes().toBytes();
        List<InspectionFindingDto> findings = new ArrayList<>();
        for (Group group : groups.values()) {
            boolean big = group.totalBytes >= threshold;
            if (!big && !group.kind.business()) {
                continue;
            }
            FindingBuilder finding = context.finding(this, group.local + "|" + group.kind.label())
                    .severity(Severity.CRITICAL)
                    .title(big
                            ? "ThreadLocal потоков пула держит " + Bytes.format(group.totalBytes) + ": "
                            + group.kind.label()
                            : "Бизнес-объект " + GcRootPathResolver.simpleName(group.kind.label())
                            + " застрял в ThreadLocal потоков пула")
                    .wasted(group.totalBytes)
                    .affected(group.threads.size())
                    .suspect(group.holderClass != null ? group.holderClass : group.kind.label(), group.holderField)
                    .pathOf(group.largest)
                    .evidence("ThreadLocal: " + group.local)
                    .evidence("Тип значения: " + group.kind.label())
                    .evidence(String.format("Потоков с таким значением: %d из %d потоков пула; всего %s, максимум"
                                    + " на поток %s", group.threads.size(), poolThreads,
                            Bytes.format(group.totalBytes), Bytes.format(group.maxPerThread)))
                    .evidence("Потоки: " + String.join(", ", group.threads.stream().limit(12).toList())
                            + (group.threads.size() > 12 ? ", …" : ""));
            if (group.stale) {
                finding.evidence("Записи устаревшие: сам ThreadLocal уже собран GC, но значение живёт в"
                        + " ThreadLocalMap, пока жив поток");
            }
            findings.add(finding.why(WHY).fix(FIX).build());
        }
        return findings;
    }

    private record ValueKind(String label, boolean business) {
    }

    /** {@code com.acme.Ctx}, or {@code java.util.ArrayList<com.acme.Order>} when a collection holds app objects. */
    private static ValueKind describeValue(HeapReader reader, Instance value) {
        String type = HeapReader.className(value);
        if (isBusiness(type)) {
            return new ValueKind(type, true);
        }
        if (HeapReader.isCollection(value)) {
            String[] element = {null};
            reader.forEachElement(value, VALUE_PEEK, item -> {
                String itemType = HeapReader.className(item);
                if (isBusiness(itemType)) {
                    element[0] = itemType;
                    return false;
                }
                return true;
            });
            if (element[0] != null) {
                return new ValueKind(type + "<" + element[0] + ">", true);
            }
        }
        return new ValueKind(type, false);
    }

    private static boolean isBusiness(String className) {
        return PackageCategorizer.categorize(className) == PackageCategory.APPLICATION;
    }

    private static String threadLocalLabel(HeapReader reader, Instance threadLocal) {
        HeapReader.Owner owner = reader.owner(threadLocal);
        String type = HeapReader.className(threadLocal);
        String suffix = "java.lang.ThreadLocal".equals(type) ? "" : " (" + type + ")";
        return (owner == null ? "#" + threadLocal.getInstanceId() : owner.describe()) + suffix;
    }

    private static final String STALE = "<устаревшая запись: ThreadLocal уже недостижим>";

    private static final class Group {
        final String local;
        final ValueKind kind;
        final boolean stale;
        final Set<String> threads = new LinkedHashSet<>();
        final String holderClass;
        final String holderField;
        long totalBytes;
        long maxPerThread;
        Instance largest;

        Group(String local, ValueKind kind, boolean stale) {
            this.local = local;
            this.kind = kind;
            this.stale = stale;
            int in = local.indexOf(" in ");
            if (local.startsWith("static ") && in > 0) {
                holderField = local.substring("static ".length(), in);
                int space = local.indexOf(' ', in + 4);
                holderClass = local.substring(in + 4, space < 0 ? local.length() : space);
            } else {
                holderField = null;
                holderClass = null;
            }
        }

        void add(String thread, Instance value) {
            long retained = value.getRetainedSize();
            threads.add(thread);
            totalBytes += retained;
            if (retained >= maxPerThread) {
                maxPerThread = retained;
                largest = value;
            }
        }
    }

    private static final String WHY = """
            Потоки пулов WildFly (default task-* Undertow/EE, XNIO I/O, ejb-client-*, managed executors) создаются \
            при старте и живут столько же, сколько сервер. Один поток обслуживает тысячи запросов подряд.

            ThreadLocal хранит значение в ThreadLocalMap самого потока: ключ (ThreadLocal) — слабая ссылка, \
            значение — сильная. Если код положил значение через set() и не вызвал remove() в finally, значение \
            остаётся в потоке навсегда. Его не освободят ни конец запроса, ни undeploy.

            Последствия:
              • утечка памяти: размер значения × число потоков пула (см. «всего» в фактах);
              • утечка данных между запросами: следующий запрос другого пользователя на этом потоке увидит чужой \
            контекст (tenant, пользователь, транзакция) — это ошибка безопасности, а не только памяти;
              • если класс значения или ThreadLocal принадлежит деплойменту, значение держит его ClassLoader, и \
            после redeploy течёт Metaspace.

            «Устаревшая запись» означает, что сам ThreadLocal уже собран (например, его создавали заново на каждый \
            вызов), а значение до сих пор висит в таблице потока.""";

    private static final String FIX = """
            // Правило: каждый set() — в паре с remove() в finally
            public final class RequestContext {
                private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

                public static <T> T with(UserContext ctx, Supplier<T> work) {
                    CURRENT.set(ctx);
                    try {
                        return work.get();
                    } finally {
                        CURRENT.remove();          // НЕ set(null): remove() удаляет запись из ThreadLocalMap
                    }
                }
            }

            // В фильтре / интерсепторе
            @Provider
            public class ContextFilter implements ContainerRequestFilter, ContainerResponseFilter {
                public void filter(ContainerRequestContext req) { RequestContext.CURRENT.set(resolve(req)); }
                public void filter(ContainerRequestContext req, ContainerResponseContext res) {
                    RequestContext.CURRENT.remove();
                }
            }

            // Ещё лучше — вообще без ThreadLocal: request-scoped CDI-бин живёт ровно один запрос
            @RequestScoped
            public class UserContextHolder {
                private UserContext context;
                // getters / setters
            }

            // MDC логгера тоже ThreadLocal: MDC.put(...) → в finally MDC.clear()
            // Не создавайте ThreadLocal на каждый вызов (new ThreadLocal<>() в методе) — только static final.""";
}
