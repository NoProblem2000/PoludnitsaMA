package com.poludnitsa.inspector.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** A snapshot comparison to put into a report: current minus baseline. */
public record DiffPairDto(
        @NotNull @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String baselineId,
        @NotNull @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") String currentId
) {
}
