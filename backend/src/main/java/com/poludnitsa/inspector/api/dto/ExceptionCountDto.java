package com.poludnitsa.inspector.api.dto;

public record ExceptionCountDto(
        String className,
        long count
) {
}
