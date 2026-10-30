package com.poludnitsa.inspector.common;

import java.util.Locale;

/** Human-readable byte sizes for messages. */
public final class Bytes {

    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private Bytes() {
    }

    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
