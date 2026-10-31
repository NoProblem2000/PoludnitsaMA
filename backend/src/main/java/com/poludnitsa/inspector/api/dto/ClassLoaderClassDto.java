package com.poludnitsa.inspector.api.dto;

/** A class defined by one class loader, with its instances. */
public record ClassLoaderClassDto(
        String className,
        long instances,
        long shallowBytes
) {
}
