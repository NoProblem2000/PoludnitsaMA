package com.poludnitsa.inspector.oql;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import org.netbeans.spi.scripting.EngineProvider;
import org.openjdk.nashorn.api.scripting.ClassFilter;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

/**
 * The JavaScript engine the NetBeans OQL engine runs on, locked down.
 *
 * <p>OQL queries are JavaScript ({@code hat.js} of VisualVM). Unrestricted Nashorn gives a query the whole Java
 * platform ({@code Java.type('java.lang.Runtime').getRuntime().exec(...)}), i.e. code execution as the OS user of the
 * inspector, which is the user of the WildFly servers. Any local process that can reach {@code 127.0.0.1:8080} could
 * use it. So:
 * <ul>
 *   <li>a {@link ClassFilter} lets scripts reach only the Java classes {@code hat.js} itself uses (heap model, a few
 *       {@code java.lang}/{@code java.util} types); {@code Java.type}, {@code Packages}, {@code java.*} and
 *       {@code load()} of anything else fail;</li>
 *   <li>Nashorn's {@code engine} and {@code context} globals are replaced by non-writable, non-configurable
 *       {@code undefined}: the engine object leads to its factory and from there to an unrestricted engine.</li>
 * </ul>
 * NetBeans' scripting API registers factories of {@link EngineProvider}s by their engine name before the ones found by
 * {@code javax.script}, so {@code getEngineByName("JavaScript")} in the OQL engine returns this one (registered in
 * {@code META-INF/services/org.netbeans.spi.scripting.EngineProvider}). {@link OqlExecutionService} verifies the
 * sandbox on every new OQL engine and refuses to run queries if it is not in place.
 */
public final class SandboxedJavaScript implements EngineProvider {

    /** Java classes {@code hat.js} references; nothing else is reachable from a query. */
    static final Set<String> ALLOWED_CLASSES = Set.of(
            "java.lang.Object", "java.lang.String", "java.lang.Double", "java.lang.Integer", "java.lang.Long",
            "java.lang.Boolean",
            "java.util.ArrayList", "java.util.HashMap", "java.util.Iterator", "java.util.Enumeration",
            "java.util.StringTokenizer",
            "org.netbeans.lib.profiler.heap.Field", "org.netbeans.lib.profiler.heap.FieldValue",
            "org.netbeans.lib.profiler.heap.GCRoot", "org.netbeans.lib.profiler.heap.Instance",
            "org.netbeans.lib.profiler.heap.JavaClass", "org.netbeans.lib.profiler.heap.ObjectArrayInstance",
            "org.netbeans.lib.profiler.heap.PrimitiveArrayInstance",
            "org.netbeans.modules.profiler.oql.engine.api.impl.ReachableExcludes",
            "org.netbeans.modules.profiler.oql.engine.api.impl.ReachableObjects");

    static final ClassFilter FILTER = ALLOWED_CLASSES::contains;

    /** Engines created by this factory; lets the OQL service tell a sandboxed engine from a foreign one. */
    static final AtomicLong CREATED = new AtomicLong();

    private static final String LOCK_GLOBALS = """
            (function (global) {
                ['engine', 'context'].forEach(function (name) {
                    Object.defineProperty(global, name,
                            { value: undefined, writable: false, configurable: false, enumerable: false });
                });
            })(this);""";

    @Override
    public List<ScriptEngineFactory> factories() {
        return List.of(new Factory());
    }

    /** Named "JavaScript": the OQL engine asks for that name. */
    static final class Factory implements ScriptEngineFactory {

        private final NashornScriptEngineFactory nashorn = new NashornScriptEngineFactory();

        @Override
        public String getEngineName() {
            return "JavaScript";
        }

        @Override
        public String getEngineVersion() {
            return nashorn.getEngineVersion();
        }

        @Override
        public List<String> getExtensions() {
            return List.of("js");
        }

        @Override
        public List<String> getMimeTypes() {
            return List.of("application/javascript", "text/javascript");
        }

        @Override
        public List<String> getNames() {
            return List.of("JavaScript", "javascript", "js", "sandboxed-nashorn");
        }

        @Override
        public String getLanguageName() {
            return "ECMAScript";
        }

        @Override
        public String getLanguageVersion() {
            return nashorn.getLanguageVersion();
        }

        @Override
        public Object getParameter(String key) {
            return nashorn.getParameter(key);
        }

        @Override
        public String getMethodCallSyntax(String object, String method, String... args) {
            return nashorn.getMethodCallSyntax(object, method, args);
        }

        @Override
        public String getOutputStatement(String toDisplay) {
            return nashorn.getOutputStatement(toDisplay);
        }

        @Override
        public String getProgram(String... statements) {
            return nashorn.getProgram(statements);
        }

        @Override
        public ScriptEngine getScriptEngine() {
            ScriptEngine engine = nashorn.getScriptEngine(new String[0], SandboxedJavaScript.class.getClassLoader(),
                    FILTER);
            try {
                engine.eval(LOCK_GLOBALS);
            } catch (ScriptException e) {
                throw new IllegalStateException("Cannot lock down the OQL script engine", e);
            }
            CREATED.incrementAndGet();
            return engine;
        }
    }
}
