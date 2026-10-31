package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * An OQL query against an open heap dump ({@code select <expr> from [instanceof] <class> <var> [where <cond>]}, or a
 * plain expression such as {@code heap.findClass('java.lang.String')}).
 *
 * @param limit          rows returned; {@code null}: {@code inspector.oql.default-row-limit}
 * @param timeoutSeconds {@code null}: {@code inspector.oql.query-timeout}
 */
public record OqlQueryRequestDto(
        @NotBlank String query,
        @Min(1) @Max(100_000) Integer limit,
        @Min(1) @Max(3_600) Integer timeoutSeconds
) {
}
