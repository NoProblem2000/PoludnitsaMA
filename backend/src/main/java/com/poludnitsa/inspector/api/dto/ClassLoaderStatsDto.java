package com.poludnitsa.inspector.api.dto;

import java.util.List;

/**
 * Memory of the classes one class loader defined.
 *
 * @param objectId        heap id of the loader instance; {@code null} for the bootstrap loader
 * @param loaderClassName class of the loader, e.g. {@code org.jboss.modules.ModuleClassLoader}
 * @param displayName     module name when known ({@code deployment.order-service.war}), else the loader class
 * @param moduleName      JBoss Modules name of a {@link ClassLoaderKind#JBOSS_MODULE} loader, else {@code null}
 * @param classCount      classes this loader defined that have instances or static state
 * @param instances       instances of those classes
 * @param shallowBytes    shallow size of those instances
 * @param retainedBytes   retained size of the loader instance itself; HPROF has no loader-to-class edge and class
 *                        objects are GC roots, so its classes and their static state are not included;
 *                        {@code null} for bootstrap
 * @param topClasses      the loader's classes with the largest shallow size
 */
public record ClassLoaderStatsDto(
        Long objectId,
        String loaderClassName,
        String displayName,
        String moduleName,
        ClassLoaderKind kind,
        int classCount,
        long instances,
        long shallowBytes,
        Long retainedBytes,
        List<ClassLoaderClassDto> topClasses
) {
    public ClassLoaderStatsDto {
        topClasses = topClasses == null ? List.of() : List.copyOf(topClasses);
    }
}
