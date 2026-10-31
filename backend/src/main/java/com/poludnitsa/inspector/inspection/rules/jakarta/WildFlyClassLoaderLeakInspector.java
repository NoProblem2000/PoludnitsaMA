package com.poludnitsa.inspector.inspection.rules.jakarta;

import com.poludnitsa.inspector.api.dto.FindingCategory;
import com.poludnitsa.inspector.api.dto.InspectionFindingDto;
import com.poludnitsa.inspector.api.dto.Severity;
import com.poludnitsa.inspector.common.Bytes;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.inspection.FindingBuilder;
import com.poludnitsa.inspector.inspection.InspectionContext;
import com.poludnitsa.inspector.inspection.LeakInspectionRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.springframework.stereotype.Component;

/**
 * Deployment class loaders ({@code org.jboss.modules.ModuleClassLoader} of {@code deployment.*} modules) that are
 * still in the heap although WildFly no longer uses them: after undeploy/redeploy the active module is registered in
 * {@code ModuleLoader.moduleMap} through a {@code FutureModule}; a loader whose module is not referenced from there
 * is dead and only kept alive by a leaking reference, together with all its classes (Metaspace).
 */
@Component
public class WildFlyClassLoaderLeakInspector implements LeakInspectionRule {

    static final String LOADER = "org.jboss.modules.ModuleClassLoader";
    static final String FUTURE_MODULE = "org.jboss.modules.ModuleLoader$FutureModule";
    /** Rough average Metaspace footprint of one loaded class; only used for an estimate in the text. */
    private static final long METASPACE_PER_CLASS = 6 * 1024;

    @Override
    public String id() {
        return "wildfly.classloader-leak";
    }

    @Override
    public String title() {
        return "Утечка ClassLoader'ов деплойментов WildFly (Metaspace)";
    }

    @Override
    public FindingCategory category() {
        return FindingCategory.JAKARTA_WILDFLY;
    }

    @Override
    public boolean isApplicable(InspectionContext context) {
        return !context.reader().classesNamed(LOADER).isEmpty();
    }

    @Override
    public List<InspectionFindingDto> inspect(InspectionContext context) {
        HeapReader reader = context.reader();
        Map<String, List<Instance>> loadersByModule = new TreeMap<>();
        for (Instance loader : HeapReader.instancesOf(reader.withSubclasses(LOADER))) {
            if (context.outOfBudget()) {
                break;
            }
            String name = reader.jbossModuleName(loader);
            if (name != null && name.startsWith("deployment.")) {
                loadersByModule.computeIfAbsent(name, k -> new ArrayList<>()).add(loader);
            }
        }
        if (loadersByModule.isEmpty()) {
            return List.of();
        }
        boolean registryVisible = !reader.classesNamed(FUTURE_MODULE).isEmpty();
        Map<Long, LoadedClasses> classesPerLoader = classesPerLoader(context.heap());
        Map<Long, List<String>> threadsPerLoader = threadsByContextLoader(context);

        List<InspectionFindingDto> findings = new ArrayList<>();
        loadersByModule.forEach((module, loaders) -> {
            if (registryVisible) {
                long active = loaders.stream().filter(loader -> isActive(reader, loader)).count();
                for (Instance loader : loaders) {
                    if (!isActive(reader, loader)) {
                        findings.add(staleLoader(context, module, loader, loaders.size(), active,
                                classesPerLoader.getOrDefault(loader.getInstanceId(), LoadedClasses.NONE),
                                threadsPerLoader.getOrDefault(loader.getInstanceId(), List.of())));
                    }
                }
            } else if (loaders.size() > 1) {
                findings.add(duplicateLoaders(context, module, loaders, classesPerLoader, threadsPerLoader));
            }
        });
        return findings;
    }

    private InspectionFindingDto staleLoader(InspectionContext context, String module, Instance loader, int copies,
                                             long active, LoadedClasses classes, List<String> threads) {
        HeapReader.Owner owner = context.reader().owner(loader);
        FindingBuilder finding = context.finding(this, String.valueOf(loader.getInstanceId()))
                .severity(Severity.CRITICAL)
                .title("ClassLoader деплоймента " + module + " не выгружен после undeploy/redeploy")
                .wasted(loader.getRetainedSize() + classes.staticBytes())
                .affected(classes.count())
                .suspect(owner != null ? owner.className() : LOADER, owner != null ? owner.field() : null)
                .pathOf(loader)
                .evidence(String.format("Копий ClassLoader'а '%s' в куче: %d, из них активных (в ModuleLoader): %d",
                        module, copies, active))
                .evidence(String.format("Классов, загруженных мёртвым ClassLoader'ом: %,d (≈ %s Metaspace, оценка)",
                        classes.count(), Bytes.format(classes.count() * METASPACE_PER_CLASS)))
                .evidence("Статические поля этих классов удерживают в heap: " + Bytes.format(classes.staticBytes()))
                .evidence("Heap, удерживаемый самим ClassLoader'ом: " + Bytes.format(loader.getRetainedSize()));
        if (owner != null) {
            finding.evidence("Ближайший держатель: " + owner.describe());
        }
        threads.stream().limit(10).forEach(thread ->
                finding.evidence("Поток '" + thread + "' держит его как contextClassLoader"));
        return finding.why(WHY).fix(FIX).build();
    }

    private InspectionFindingDto duplicateLoaders(InspectionContext context, String module, List<Instance> loaders,
                                                  Map<Long, LoadedClasses> classesPerLoader,
                                                  Map<Long, List<String>> threadsPerLoader) {
        List<Instance> bySize = new ArrayList<>(loaders);
        bySize.sort(Comparator.comparingLong(Instance::getRetainedSize));
        long wasted = 0;
        for (int i = 0; i < bySize.size() - 1; i++) {
            wasted += bySize.get(i).getRetainedSize();
        }
        FindingBuilder finding = context.finding(this, "duplicates:" + module)
                .severity(Severity.WARNING)
                .title(loaders.size() + " копий ClassLoader'а деплоймента " + module)
                .wasted(wasted)
                .affected(loaders.size())
                .suspect(LOADER, null)
                .pathOf(bySize.get(0))
                .evidence("Реестр ModuleLoader не найден в дампе: активную копию определить нельзя, учтены все,"
                        + " кроме самой большой");
        for (Instance loader : loaders) {
            LoadedClasses classes = classesPerLoader.getOrDefault(loader.getInstanceId(), LoadedClasses.NONE);
            finding.evidence(String.format("ClassLoader #%d: %,d классов (статика %s), heap %s, потоков с ним как"
                            + " contextClassLoader: %d", loader.getInstanceId(), classes.count(),
                    Bytes.format(classes.staticBytes()), Bytes.format(loader.getRetainedSize()),
                    threadsPerLoader.getOrDefault(loader.getInstanceId(), List.of()).size()));
        }
        return finding.why(WHY).fix(FIX).build();
    }

    private static boolean isActive(HeapReader reader, Instance loader) {
        Instance module = HeapReader.objectField(loader, "module");
        for (HeapReader.Referrer referrer : reader.referrers(module, 64)) {
            if (referrer.holderClass().equals(FUTURE_MODULE)) {
                return true;
            }
        }
        return false;
    }

    /** Classes of a loader and what their static fields retain (HPROF has no loader → class edge). */
    private record LoadedClasses(int count, long staticBytes) {
        static final LoadedClasses NONE = new LoadedClasses(0, 0);

        LoadedClasses plus(long bytes) {
            return new LoadedClasses(count + 1, staticBytes + bytes);
        }
    }

    private static Map<Long, LoadedClasses> classesPerLoader(Heap heap) {
        Map<Long, LoadedClasses> classes = new HashMap<>();
        for (Object element : heap.getAllClasses()) {
            JavaClass javaClass = (JavaClass) element;
            Instance loader = javaClass.getClassLoader();
            if (loader != null) {
                Instance classObject = heap.getInstanceByID(javaClass.getJavaClassId());
                long statics = classObject == null ? 0 : classObject.getRetainedSize();
                classes.merge(loader.getInstanceId(), LoadedClasses.NONE.plus(statics),
                        (a, b) -> new LoadedClasses(a.count() + b.count(), a.staticBytes() + b.staticBytes()));
            }
        }
        return classes;
    }

    private static Map<Long, List<String>> threadsByContextLoader(InspectionContext context) {
        HeapReader reader = context.reader();
        Map<Long, List<String>> threads = new HashMap<>();
        for (Instance thread : HeapReader.instancesOf(reader.withSubclasses("java.lang.Thread"))) {
            Instance loader = HeapReader.objectField(thread, "contextClassLoader");
            if (loader != null) {
                String name = reader.string(HeapReader.objectField(thread, "name"), 128);
                threads.computeIfAbsent(loader.getInstanceId(), k -> new ArrayList<>())
                        .add(name == null ? "#" + thread.getInstanceId() : name);
            }
        }
        return threads;
    }

    private static final String WHY = """
            При undeploy или redeploy WildFly отпускает ModuleClassLoader деплоймента. Вместе с ним должны \
            выгрузиться все классы приложения, их статические поля и Metaspace. Это возможно, только если на \
            ClassLoader, на любой его класс или на любой объект этих классов не осталось ни одной ссылки \
            из «вечного» мира сервера.

            Здесь ссылка осталась. Чаще всего это:
              • поток, созданный приложением (new Thread, Executors, java.util.Timer), не остановленный в \
            @PreDestroy: у потока contextClassLoader = загрузчик деплоймента;
              • значение в ThreadLocal потока пула (default task-*, XNIO), которое не удалили через remove();
              • JDBC-драйвер из WEB-INF/lib, зарегистрированный в java.sql.DriverManager (класс JDK, живёт вечно);
              • статический кэш библиотеки сервера или JDK (java.beans.Introspector, JAXB, Jackson TypeFactory, \
            ResourceBundle), куда попал класс приложения;
              • shutdown hook, MBean, java.util.logging.Handler или Hazelcast-слушатель, зарегистрированные \
            приложением и не снятые при остановке.

            Каждый redeploy добавляет ещё одну полную копию всех классов. Metaspace растёт, пока не закончится \
            OutOfMemoryError: Metaspace. Путь до GC Root ниже показывает, кто именно держит загрузчик.""";

    private static final String FIX = """
            // 1. Свои потоки и пулы останавливаем при остановке приложения — или берём управляемые сервером
            @Singleton
            @Startup
            public class BackgroundJobs {
                @Resource
                private ManagedScheduledExecutorService scheduler;   // пул WildFly, сам чистится при undeploy

                private ExecutorService ownPool;                     // если всё же нужен свой

                @PostConstruct
                void start() {
                    ownPool = Executors.newFixedThreadPool(4);
                }

                @PreDestroy
                void stop() {
                    ownPool.shutdownNow();                           // иначе поток держит contextClassLoader
                }
            }

            // 2. ThreadLocal — только с remove() в finally
            try {
                RequestContext.CURRENT.set(ctx);
                chain.doFilter(request, response);
            } finally {
                RequestContext.CURRENT.remove();
            }

            // 3. JDBC-драйвер ставим модулем WildFly (module.xml + datasource), а не в WEB-INF/lib.
            //    Если драйвер всё же в WAR — снимаем регистрацию при остановке:
            @WebListener
            public class JdbcDriverCleanup implements ServletContextListener {
                @Override
                public void contextDestroyed(ServletContextEvent event) {
                    ClassLoader app = Thread.currentThread().getContextClassLoader();
                    DriverManager.drivers()
                            .filter(driver -> driver.getClass().getClassLoader() == app)
                            .forEach(driver -> {
                                try {
                                    DriverManager.deregisterDriver(driver);
                                } catch (SQLException ignored) {
                                }
                            });
                }
            }

            // 4. Всё, что зарегистрировали в долгоживущем объекте (shutdown hook, MBean, listener, Timer), —
            //    снимаем в @PreDestroy / contextDestroyed.""";
}
