package com.poludnitsa.inspector.oql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OqlExecutionServiceTest {

    @Test
    void errorMessagesLoseTheirWrappers() {
        assertThat(OqlExecutionService.clean("java.lang.RuntimeException: java.lang.ClassNotFoundException: "
                + "java.lang.Runtime")).isEqualTo("class not accessible from OQL: java.lang.Runtime");
        assertThat(OqlExecutionService.clean("javax.script.ScriptException: TypeError: x is not a function"))
                .isEqualTo("TypeError: x is not a function");
        assertThat(OqlExecutionService.clean(null)).isEqualTo("OQL error");
    }

    @Test
    void sandboxAllowsOnlyTheClassesTheOqlScriptsUse() {
        assertThat(SandboxedJavaScript.FILTER.exposeToScripts("org.netbeans.lib.profiler.heap.Instance")).isTrue();
        assertThat(SandboxedJavaScript.FILTER.exposeToScripts("java.util.ArrayList")).isTrue();
        assertThat(SandboxedJavaScript.FILTER.exposeToScripts("java.lang.Runtime")).isFalse();
        assertThat(SandboxedJavaScript.FILTER.exposeToScripts("java.lang.ProcessBuilder")).isFalse();
        assertThat(SandboxedJavaScript.FILTER.exposeToScripts("java.io.File")).isFalse();
        assertThat(SandboxedJavaScript.FILTER.exposeToScripts("org.netbeans.lib.profiler.heap.HeapFactory")).isFalse();
    }
}
