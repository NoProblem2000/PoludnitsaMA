package com.poludnitsa.inspector.api.dto;

import java.util.List;

/** One page of a long list (virtualized tables fetch pages on scroll). */
public record PageDto<T>(
        List<T> items,
        int total,
        int offset,
        int limit
) {
    public PageDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
