package com.poludnitsa.inspector.api.dto;

/**
 * Free-space check before a heap dump: {@code usableBytes >= factor * heapUsedBytes}.
 *
 * @param directory     where the target JVM will write the dump
 * @param requiredBytes {@code factor * heapUsedBytes}: the dump plus the NetBeans index files
 */
public record DiskSpaceCheckDto(
        long pid,
        String directory,
        long heapUsedBytes,
        double factor,
        long requiredBytes,
        long usableBytes,
        boolean sufficient
) {
}
