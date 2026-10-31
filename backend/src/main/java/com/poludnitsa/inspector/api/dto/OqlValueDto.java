package com.poludnitsa.inspector.api.dto;

import java.util.List;
import java.util.Map;

/**
 * One cell of an OQL result.
 *
 * @param text         display text: the value, {@code "Order@0x1a2b"} for objects, a string preview for strings
 * @param objectId     heap object id for {@link OqlValueType#INSTANCE} (and of the class object for CLASS)
 * @param className    class of an instance, name of a class
 * @param shallowSize  instance size; for CLASS: size of all its instances
 * @param retainedSize retained size of an instance, when the dump's retained sizes are computed
 * @param items        first elements of a LIST
 * @param fields       properties of a nested OBJECT
 * @param totalItems   number of elements of a LIST when known
 */
public record OqlValueDto(
        OqlValueType type,
        String text,
        Long objectId,
        String className,
        Long shallowSize,
        Long retainedSize,
        List<OqlValueDto> items,
        Map<String, OqlValueDto> fields,
        Long totalItems
) {
    public static OqlValueDto simple(OqlValueType type, String text) {
        return new OqlValueDto(type, text, null, null, null, null, null, null, null);
    }
}
