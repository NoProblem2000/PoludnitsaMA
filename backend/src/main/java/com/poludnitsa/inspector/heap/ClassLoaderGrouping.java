package com.poludnitsa.inspector.heap;

import com.poludnitsa.inspector.api.dto.ClassLoaderClassDto;
import com.poludnitsa.inspector.api.dto.ClassLoaderKind;
import com.poludnitsa.inspector.api.dto.ClassLoaderStatsDto;
import com.poludnitsa.inspector.common.JvmTypeNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;

/**
 * Memory of a heap dump by the class loader that defined the classes: the bootstrap loader, the platform and
 * application loaders, and on WildFly one {@code ModuleClassLoader} per server module and deployment, named by its
 * module ({@code deployment.order-service.war}).
 *
 * <p>Instances count for the loader of their class. The retained size is that of the loader instance only: HPROF has
 * no loader-to-class edge and class objects are GC roots, so classes and their statics are not part of it.
 */
public final class ClassLoaderGrouping {

    static final String MODULE_CLASS_LOADER = "org.jboss.modules.ModuleClassLoader";
    private static final String PLATFORM = "jdk.internal.loader.ClassLoaders$PlatformClassLoader";
    private static final String APPLICATION = "jdk.internal.loader.ClassLoaders$AppClassLoader";
    private static final int TOP_CLASSES = 15;

    private ClassLoaderGrouping() {
    }

    private static final class Accumulator {
        final Instance loader;
        int classes;
        long instances;
        long shallow;
        final Map<String, long[]> byClass = new HashMap<>();

        Accumulator(Instance loader) {
            this.loader = loader;
        }
    }

    /** @param limit loaders reported, largest first (a WildFly server has hundreds of modules) */
    public static List<ClassLoaderStatsDto> group(Heap heap, HeapReader reader, int limit) {
        Map<Long, Accumulator> byLoader = new HashMap<>();
        for (Object element : heap.getAllClasses()) {
            JavaClass javaClass = (JavaClass) element;
            Instance loader = javaClass.getClassLoader();
            long key = loader == null ? 0 : loader.getInstanceId();
            Accumulator accumulator = byLoader.computeIfAbsent(key, k -> new Accumulator(loader));
            accumulator.classes++;
            int count = javaClass.getInstancesCount();
            if (count == 0) {
                continue;
            }
            long size = javaClass.getAllInstancesSize();
            accumulator.instances += count;
            accumulator.shallow += size;
            long[] perClass = accumulator.byClass.computeIfAbsent(JvmTypeNames.toJavaName(javaClass.getName()),
                    name -> new long[2]);
            perClass[0] += count;
            perClass[1] += size;
        }

        List<ClassLoaderStatsDto> result = new ArrayList<>(byLoader.size());
        for (Accumulator accumulator : byLoader.values()) {
            result.add(describe(heap, reader, accumulator));
        }
        result.sort(Comparator.comparingLong((ClassLoaderStatsDto s) -> s.retainedBytes() == null ? s.shallowBytes()
                : Math.max(s.retainedBytes(), s.shallowBytes())).reversed()
                .thenComparing(ClassLoaderStatsDto::displayName));
        return List.copyOf(result.subList(0, Math.min(Math.max(1, limit), result.size())));
    }

    private static ClassLoaderStatsDto describe(Heap heap, HeapReader reader, Accumulator accumulator) {
        Instance loader = accumulator.loader;
        List<ClassLoaderClassDto> top = accumulator.byClass.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(TOP_CLASSES)
                .map(e -> new ClassLoaderClassDto(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        if (loader == null) {
            return new ClassLoaderStatsDto(null, "bootstrap", "Bootstrap (java.base, JDK modules)", null,
                    ClassLoaderKind.BOOTSTRAP, accumulator.classes, accumulator.instances, accumulator.shallow, null,
                    top);
        }
        String loaderClass = JvmTypeNames.toJavaName(loader.getJavaClass().getName());
        ClassLoaderKind kind;
        String module = null;
        if (HeapReader.isInstanceOf(loader, MODULE_CLASS_LOADER)) {
            kind = ClassLoaderKind.JBOSS_MODULE;
            module = reader.jbossModuleName(loader);
        } else if (PLATFORM.equals(loaderClass)) {
            kind = ClassLoaderKind.PLATFORM;
        } else if (APPLICATION.equals(loaderClass)) {
            kind = ClassLoaderKind.APPLICATION;
        } else {
            kind = ClassLoaderKind.OTHER;
        }
        String display = module != null ? module : switch (kind) {
            case PLATFORM -> "Platform class loader";
            case APPLICATION -> "Application class loader (class path)";
            default -> GcRootPathResolver.simpleName(loaderClass) + "@0x" + Long.toHexString(loader.getInstanceId());
        };
        long retained = heap.isRetainedSizeComputed() ? loader.getRetainedSize() : -1;
        return new ClassLoaderStatsDto(loader.getInstanceId(), loaderClass, display, module, kind,
                accumulator.classes, accumulator.instances, accumulator.shallow, retained < 0 ? null : retained, top);
    }
}
