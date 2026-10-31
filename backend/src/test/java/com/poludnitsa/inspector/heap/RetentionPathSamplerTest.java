package com.poludnitsa.inspector.heap;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.GcRootPathStepDto;
import com.poludnitsa.inspector.api.dto.ReferenceKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetentionPathSamplerTest {

    @Test
    void signatureIgnoresArrayIndexesAndNamesStaticFieldsAndWeakHops() {
        List<GcRootPathStepDto> a = List.of(
                new GcRootPathStepDto(1, "class com.acme.OrderService", "static CACHE", true, "sticky class"),
                new GcRootPathStepDto(2, "java.util.HashMap", "table", false, null),
                new GcRootPathStepDto(3, "java.util.HashMap$Node[]", "[17]", false, null),
                new GcRootPathStepDto(4, "java.util.HashMap$Node", "value", false, null),
                new GcRootPathStepDto(5, "java.lang.ref.WeakReference", "referent", false, null, ReferenceKind.WEAK),
                new GcRootPathStepDto(6, "com.acme.Order", null, false, null));
        List<GcRootPathStepDto> b = List.of(
                new GcRootPathStepDto(1, "class com.acme.OrderService", "static CACHE", true, "sticky class"),
                new GcRootPathStepDto(2, "java.util.HashMap", "table", false, null),
                new GcRootPathStepDto(3, "java.util.HashMap$Node[]", "[901]", false, null),
                new GcRootPathStepDto(9, "java.util.HashMap$Node", "value", false, null),
                new GcRootPathStepDto(5, "java.lang.ref.WeakReference", "referent", false, null, ReferenceKind.WEAK),
                new GcRootPathStepDto(8, "com.acme.Order", null, false, null));

        String signature = RetentionPathSampler.signature(a);

        assertThat(signature).isEqualTo("static CACHE in OrderService → HashMap.table → HashMap$Node[] → "
                + "HashMap$Node.value → WeakReference.referent (weak)");
        assertThat(RetentionPathSampler.signature(b)).isEqualTo(signature);
    }
}
