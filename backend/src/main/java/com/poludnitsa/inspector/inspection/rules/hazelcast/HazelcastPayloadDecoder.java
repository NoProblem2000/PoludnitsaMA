package com.poludnitsa.inspector.inspection.rules.hazelcast;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Names the type stored in a Hazelcast {@code HeapData} payload from its first bytes.
 *
 * <p>Layout (Hazelcast 3.6+, big-endian header): {@code [0..3]} partition hash, {@code [4..7]} serializer type id,
 * then the serializer's data. Decoded further:
 * <ul>
 *   <li>{@code -2} DataSerializable: header byte (bit 0 = identified), then {@code factoryId, classId} or the class
 *       name;</li>
 *   <li>{@code -1} Portable: {@code factoryId, classId};</li>
 *   <li>Java serialization: an {@code ObjectOutputStream} stream ({@code AC ED 00 05}) with the class descriptor.</li>
 * </ul>
 */
public final class HazelcastPayloadDecoder {

    static final int TYPE_OFFSET = 4;
    static final int DATA_OFFSET = 8;
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");

    /** Built-in serializer ids of {@code SerializationConstants} that are stable across 3.x–5.x. */
    private static final Map<Integer, String> BUILT_IN = Map.ofEntries(
            Map.entry(0, "null"),
            Map.entry(-3, "Byte"), Map.entry(-4, "Boolean"), Map.entry(-5, "Character"), Map.entry(-6, "Short"),
            Map.entry(-7, "Integer"), Map.entry(-8, "Long"), Map.entry(-9, "Float"), Map.entry(-10, "Double"),
            Map.entry(-11, "String"), Map.entry(-12, "byte[]"), Map.entry(-13, "boolean[]"),
            Map.entry(-14, "char[]"), Map.entry(-15, "short[]"), Map.entry(-16, "int[]"), Map.entry(-17, "long[]"),
            Map.entry(-18, "float[]"), Map.entry(-19, "double[]"), Map.entry(-20, "String[]"));

    private HazelcastPayloadDecoder() {
    }

    public static int typeId(byte[] payload) {
        return payload.length >= DATA_OFFSET ? readInt(payload, TYPE_OFFSET) : Integer.MIN_VALUE;
    }

    public static String describe(byte[] payload) {
        if (payload.length < DATA_OFFSET) {
            return "пустой или повреждённый HeapData";
        }
        int type = readInt(payload, TYPE_OFFSET);
        String javaClass = javaSerializedClass(payload);
        if (javaClass != null) {
            return "Java Serializable " + javaClass;
        }
        switch (type) {
            case -2 -> {
                if (payload.length > DATA_OFFSET) {
                    boolean identified = (payload[DATA_OFFSET] & 0x01) != 0;
                    if (identified && payload.length >= DATA_OFFSET + 9) {
                        return String.format("IdentifiedDataSerializable factoryId=%d classId=%d",
                                readInt(payload, DATA_OFFSET + 1), readInt(payload, DATA_OFFSET + 5));
                    }
                    String name = readClassName(payload, DATA_OFFSET + 1);
                    if (name != null) {
                        return "DataSerializable " + name;
                    }
                }
                return "DataSerializable";
            }
            case -1 -> {
                return payload.length >= DATA_OFFSET + 8
                        ? String.format("Portable factoryId=%d classId=%d", readInt(payload, DATA_OFFSET),
                        readInt(payload, DATA_OFFSET + 4))
                        : "Portable";
            }
            default -> {
                String builtIn = BUILT_IN.get(type);
                if (builtIn != null) {
                    return builtIn;
                }
                return type > 0
                        ? "пользовательский StreamSerializer typeId=" + type
                        : "встроенный сериализатор Hazelcast typeId=" + type;
            }
        }
    }

    /** Class of the first object in an {@code ObjectOutputStream} stream near the start of the data. */
    static String javaSerializedClass(byte[] payload) {
        int limit = Math.min(payload.length - 8, DATA_OFFSET + 64);
        for (int i = DATA_OFFSET; i <= limit; i++) {
            if ((payload[i] & 0xFF) == 0xAC && (payload[i + 1] & 0xFF) == 0xED && payload[i + 2] == 0x00
                    && payload[i + 3] == 0x05) {
                // TC_OBJECT 0x73, TC_CLASSDESC 0x72, u2 length, modified UTF-8 name
                if (payload[i + 4] == 0x73 && payload[i + 5] == 0x72 && i + 8 <= payload.length) {
                    int length = ((payload[i + 6] & 0xFF) << 8) | (payload[i + 7] & 0xFF);
                    if (length > 0 && i + 8 + length <= payload.length) {
                        String name = new String(payload, i + 8, length, StandardCharsets.UTF_8);
                        if (CLASS_NAME.matcher(name).matches()) {
                            return name;
                        }
                    }
                }
                return "(Java serialization stream)";
            }
        }
        return null;
    }

    /** A class name written with Hazelcast's writeString (int length) or the 3.x writeUTF (u2 length). */
    static String readClassName(byte[] payload, int offset) {
        if (offset + 4 <= payload.length) {
            int length = readInt(payload, offset);
            if (length > 0 && length <= 512 && offset + 4 + length <= payload.length) {
                String name = new String(payload, offset + 4, length, StandardCharsets.UTF_8);
                if (CLASS_NAME.matcher(name).matches()) {
                    return name;
                }
            }
        }
        if (offset + 2 <= payload.length) {
            int length = ((payload[offset] & 0xFF) << 8) | (payload[offset + 1] & 0xFF);
            if (length > 0 && offset + 2 + length <= payload.length) {
                String name = new String(payload, offset + 2, length, StandardCharsets.UTF_8);
                if (CLASS_NAME.matcher(name).matches()) {
                    return name;
                }
            }
        }
        return null;
    }

    static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }
}
