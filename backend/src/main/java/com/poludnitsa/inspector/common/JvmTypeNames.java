package com.poludnitsa.inspector.common;

/** Converts JVM descriptors ({@code [B}, {@code [[Ljava.lang.String;}) to source-like names ({@code byte[]}, ...). */
public final class JvmTypeNames {

    private JvmTypeNames() {
    }

    public static String toJavaName(String name) {
        if (name == null || name.isEmpty() || name.charAt(0) != '[') {
            return name;
        }
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        String element = name.substring(dims);
        String base = switch (element) {
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "I" -> "int";
            case "J" -> "long";
            case "F" -> "float";
            case "D" -> "double";
            default -> element.startsWith("L") && element.endsWith(";")
                    ? element.substring(1, element.length() - 1)
                    : element;
        };
        return base + "[]".repeat(dims);
    }
}
