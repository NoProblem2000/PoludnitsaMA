package com.poludnitsa.inspector.jmx;

import com.poludnitsa.inspector.api.dto.MemoryPoolKind;
import com.poludnitsa.inspector.api.dto.MemoryPoolType;
import java.util.Locale;

/** Resolves the collector-independent role of a memory pool from its name. */
public final class MemoryPoolClassifier {

    private MemoryPoolClassifier() {
    }

    public static MemoryPoolKind kind(String poolName, MemoryPoolType type) {
        String n = poolName.toLowerCase(Locale.ROOT);
        if (n.contains("eden")) {
            return MemoryPoolKind.EDEN;
        }
        if (n.contains("survivor")) {
            return MemoryPoolKind.SURVIVOR;
        }
        if (n.contains("old") || n.contains("tenured")) {
            return MemoryPoolKind.OLD;
        }
        if (n.equals("metaspace")) {
            return MemoryPoolKind.METASPACE;
        }
        if (n.contains("compressed class")) {
            return MemoryPoolKind.COMPRESSED_CLASS_SPACE;
        }
        if (n.contains("codeheap") || n.contains("code cache") || n.equals("codecache")) {
            return MemoryPoolKind.CODE_CACHE;
        }
        return type == MemoryPoolType.HEAP ? MemoryPoolKind.OTHER_HEAP : MemoryPoolKind.OTHER_NON_HEAP;
    }
}
