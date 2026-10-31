package com.poludnitsa.inspector.api.dto;

public enum OqlValueType {
    /** a heap object (instance or array) */
    INSTANCE,
    /** a {@code JavaClass} of the heap */
    CLASS,
    STRING,
    NUMBER,
    BOOLEAN,
    /** a script array, Java collection or iterator; {@link OqlValueDto#items()} holds the first elements */
    LIST,
    /** a script object nested in a cell; {@link OqlValueDto#fields()} holds its properties */
    OBJECT,
    NULL,
    OTHER
}
