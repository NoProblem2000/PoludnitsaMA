package com.poludnitsa.inspector.oql;

import com.poludnitsa.inspector.api.dto.GcRootPathDto;
import com.poludnitsa.inspector.api.dto.OqlQueryRequestDto;
import com.poludnitsa.inspector.api.dto.OqlResultDto;
import com.poludnitsa.inspector.api.dto.OqlValueDto;
import com.poludnitsa.inspector.api.dto.OqlValueType;
import com.poludnitsa.inspector.api.dto.PathMode;
import com.poludnitsa.inspector.common.DaemonThreadFactory;
import com.poludnitsa.inspector.common.InspectorException;
import com.poludnitsa.inspector.common.JvmTypeNames;
import com.poludnitsa.inspector.common.OperationRejectedException;
import com.poludnitsa.inspector.config.InspectorProperties;
import com.poludnitsa.inspector.heap.GcRootPathResolver;
import com.poludnitsa.inspector.heap.HeapReader;
import com.poludnitsa.inspector.snapshot.OpenHeapRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.modules.profiler.oql.engine.api.OQLEngine;
import org.netbeans.modules.profiler.oql.engine.api.OQLException;
import org.openjdk.nashorn.api.scripting.ScriptObjectMirror;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * Runs OQL ({@code select ... from ... where ...}, the VisualVM dialect) and on-demand GC root path searches against
 * heap dumps kept open by {@link OpenHeapRegistry}.
 *
 * <p>Queries run on a worker thread with a timeout ({@link OQLEngine#cancelQuery()}), one at a time per dump, and
 * return at most {@code limit} rows converted into plain values: heap objects as id, class, sizes and a short text.
 * The JavaScript engine underneath is {@link SandboxedJavaScript}; every new OQL engine is checked to really run on it
 * before the first user query.
 */
@Service
public class OqlExecutionService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OqlExecutionService.class);
    private static final int MAX_ITEMS = 20;
    private static final int MAX_DEPTH = 2;
    private static final int MAX_TEXT = 200;
    private static final String VALUE_COLUMN = "value";
    private static final String WRAPPED = "wrapped-object";

    private final OpenHeapRegistry registry;
    private final InspectorProperties.Oql props;
    private final InspectorProperties.Snapshots snapshots;
    // cached: a runaway script keeps its thread, it must not block the queries of other dumps
    private final ExecutorService executor = Executors.newCachedThreadPool(new DaemonThreadFactory("oql"));

    public OqlExecutionService(OpenHeapRegistry registry, InspectorProperties properties) {
        this.registry = registry;
        this.props = properties.oql();
        this.snapshots = properties.snapshots();
    }

    /** OQL engine of one open dump, created on first use. */
    private static final class Session {
        final OQLEngine engine;
        final HeapReader reader;
        /**
         * A timed-out query whose worker did not stop: a script stuck inside one expression ({@code while (true)})
         * never checks the cancel flag, and the NetBeans heap must not be used by two threads.
         */
        volatile CountDownLatch runaway;

        Session(OQLEngine engine, HeapReader reader) {
            this.engine = engine;
            this.reader = reader;
        }
    }

    private static final Duration STOP_GRACE = Duration.ofSeconds(5);

    /** Thrown when a cancelled query keeps running; {@code finished} opens when its worker ends. */
    private static final class RunawayQueryException extends OperationRejectedException {
        final transient CountDownLatch finished;

        RunawayQueryException(CountDownLatch finished, Duration timeout) {
            super(408, "OQL_WORKER_STUCK", "The query was cancelled after " + timeout.toSeconds() + " s but its"
                    + " script does not stop (an endless loop in one expression?). The dump stays busy until it ends;"
                    + " close the dump to release it.");
            this.finished = finished;
        }
    }

    public OqlResultDto execute(String snapshotId, OqlQueryRequestDto request) {
        if (!props.enabled()) {
            throw new OperationRejectedException(403, "OQL_DISABLED", "OQL is disabled (inspector.oql.enabled=false)");
        }
        String query = request.query().strip();
        if (query.length() > props.maxQueryLength()) {
            throw new OperationRejectedException(400, "OQL_TOO_LONG",
                    "The query has " + query.length() + " characters; at most " + props.maxQueryLength() + " allowed");
        }
        int limit = Math.min(request.limit() == null ? props.defaultRowLimit() : request.limit(), props.maxRowLimit());
        Duration timeout = request.timeoutSeconds() == null ? props.queryTimeout()
                : Duration.ofSeconds(Math.min(request.timeoutSeconds(), props.queryTimeout().toSeconds()));
        return registry.withHeap(snapshotId, open -> {
            Session session = session(open);
            CountDownLatch runaway = session.runaway;
            if (runaway != null && runaway.getCount() > 0) {
                throw new OperationRejectedException(409, "HEAP_BUSY", "A cancelled query still runs on snapshot "
                        + snapshotId + "; close the dump (DELETE /api/snapshots/" + snapshotId + "/heap) to release it");
            }
            session.runaway = null;
            long started = System.nanoTime();
            try {
                Rows rows = runWithTimeout(session.engine, timeout, () -> collect(session, query, limit));
                return toResult(snapshotId, query, rows, session, (System.nanoTime() - started) / 1_000_000);
            } catch (RunawayQueryException e) {
                session.runaway = e.finished;
                log.error("OQL query on snapshot {} did not stop after cancellation: {}", snapshotId, query);
                throw e;
            }
        });
    }

    /** Shortest path to a GC root of one object of an open dump. */
    public GcRootPathDto path(String snapshotId, long objectId, PathMode mode) {
        return registry.withHeap(snapshotId, open -> {
            Heap heap = open.heap();
            Instance instance = heap.getInstanceByID(objectId);
            if (instance == null) {
                throw new InspectorException(404, "OBJECT_NOT_FOUND",
                        "No object 0x" + Long.toHexString(objectId) + " in snapshot " + snapshotId);
            }
            GcRootPathResolver resolver = new GcRootPathResolver(heap, snapshots.maxPathDepth(), props.pathSearchLimit());
            GcRootPathResolver.ResolvedPath path = resolver.resolve(instance, mode);
            return new GcRootPathDto(objectId, mode, path.steps(), path.text(), path.rootKind(),
                    path.strongPathExists(), path.visited(), path.truncated());
        });
    }

    private Session session(OpenHeapRegistry.OpenHeap open) {
        return open.attachment(Session.class, heap -> {
            OQLEngine engine = runWithTimeout(null, Duration.ofSeconds(60), () -> {
                if (!OQLEngine.isOQLSupported()) {
                    throw new OperationRejectedException(501, "OQL_UNSUPPORTED",
                            "No JavaScript engine for OQL on the class path (nashorn-core is missing)");
                }
                return new OQLEngine(heap);
            });
            verifySandbox(engine);
            return new Session(engine, new HeapReader(heap));
        });
    }

    /**
     * Fails closed: if the OQL engine got another JavaScript engine than {@link SandboxedJavaScript} (another
     * provider on the class path, a changed NetBeans lookup), user queries could reach the Java platform.
     */
    private void verifySandbox(OQLEngine engine) {
        boolean javaReachable;
        try {
            runWithTimeout(engine, Duration.ofSeconds(30), () -> {
                engine.executeQuery("select Java.type('java.lang.System')", value -> true);
                return null;
            });
            javaReachable = true;
        } catch (OperationRejectedException e) {
            javaReachable = !"OQL_ERROR".equals(e.code());
        }
        Object[] engineGlobal = {null};
        runWithTimeout(engine, Duration.ofSeconds(30), () -> {
            engine.executeQuery("select typeof engine", value -> {
                engineGlobal[0] = value;
                return true;
            });
            return null;
        });
        if (javaReachable || !"undefined".equals(String.valueOf(engineGlobal[0]))
                || SandboxedJavaScript.CREATED.get() == 0) {
            log.error("OQL sandbox is not active (java reachable: {}, engine global: {}, sandboxed engines: {})",
                    javaReachable, engineGlobal[0], SandboxedJavaScript.CREATED.get());
            throw new OperationRejectedException(500, "OQL_SANDBOX_INACTIVE",
                    "The OQL script engine is not sandboxed; queries are refused to protect the host");
        }
    }

    // ---------------------------------------------------------------- execution

    private record Rows(List<Object> values, boolean truncated) {
    }

    private Rows collect(Session session, String query, int limit) throws OQLException {
        List<Object> values = new ArrayList<>(Math.min(limit, 1024));
        boolean[] truncated = {false};
        session.engine.executeQuery(query, value -> {
            if (values.size() >= limit) {
                truncated[0] = true;
                return true;
            }
            values.add(value);
            return session.engine.isCancelled();
        });
        return new Rows(values, truncated[0]);
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    /**
     * Runs on an OQL worker: the NetBeans scripting lookup and Nashorn resolve classes through the context class
     * loader, which must be the application's (the Spring Boot jar loader), whatever thread called us.
     */
    private <T> T runWithTimeout(OQLEngine engine, Duration timeout, Work<T> work) {
        ClassLoader loader = OqlExecutionService.class.getClassLoader();
        CountDownLatch finished = new CountDownLatch(1);
        Future<T> future = executor.submit(() -> {
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            thread.setContextClassLoader(loader);
            try {
                return work.run();
            } finally {
                thread.setContextClassLoader(previous);
                finished.countDown();
            }
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancel(engine);
            future.cancel(true);
            if (!awaitStop(finished)) {
                throw new RunawayQueryException(finished, timeout);
            }
            throw new OperationRejectedException(408, "OQL_TIMEOUT",
                    "The query was cancelled after " + timeout.toSeconds() + " s; narrow it with a where clause or"
                            + " a more specific class");
        } catch (InterruptedException e) {
            cancel(engine);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new OperationRejectedException(503, "INTERRUPTED", "Interrupted while running the query");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InspectorException inspector) {
                throw inspector;
            }
            if (cause instanceof OQLException) {
                throw new OperationRejectedException(400, "OQL_ERROR", clean(cause.getMessage()));
            }
            if (cause instanceof OutOfMemoryError) {
                throw new OperationRejectedException(507, "OQL_OUT_OF_MEMORY",
                        "The query needed more memory than the inspector has; select fewer or smaller values");
            }
            log.warn("OQL query failed", cause);
            throw new OperationRejectedException(400, "OQL_ERROR", clean(String.valueOf(cause)));
        }
    }

    /** {@code future.cancel(true)} marks the future done at once; the worker itself signals {@code finished}. */
    private static boolean awaitStop(CountDownLatch finished) {
        try {
            return finished.await(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void cancel(OQLEngine engine) {
        if (engine == null) {
            return;
        }
        try {
            engine.cancelQuery();
        } catch (OQLException | RuntimeException ignored) {
            // the worker is interrupted as well
        }
    }

    /** {@code javax.script.ScriptException: TypeError: ... in <eval> at line number 1} without the wrapping. */
    static String clean(String message) {
        if (message == null) {
            return "OQL error";
        }
        String text = message;
        for (String prefix : List.of("java.lang.RuntimeException: ", "javax.script.ScriptException: ",
                "org.netbeans.modules.profiler.oql.engine.api.OQLException: ")) {
            while (text.startsWith(prefix)) {
                text = text.substring(prefix.length());
            }
        }
        text = text.replace("java.lang.ClassNotFoundException: ", "class not accessible from OQL: ");
        return text.length() > 1000 ? text.substring(0, 1000) + "…" : text;
    }

    // ---------------------------------------------------------------- conversion

    private OqlResultDto toResult(String snapshotId, String query, Rows rows, Session session, long durationMs) {
        Set<String> columns = new LinkedHashSet<>();
        boolean plain = false;
        List<Object> unwrapped = new ArrayList<>(rows.values().size());
        for (Object value : rows.values()) {
            Object row = unwrap(value);
            unwrapped.add(row);
            if (row instanceof ScriptObjectMirror mirror && !mirror.isArray() && !mirror.isFunction()) {
                columns.addAll(mirror.keySet());
            } else {
                plain = true;
            }
        }
        if (plain || columns.isEmpty()) {
            columns.add(VALUE_COLUMN);
        }
        List<String> header = List.copyOf(columns);
        List<List<OqlValueDto>> cells = new ArrayList<>(unwrapped.size());
        for (Object row : unwrapped) {
            List<OqlValueDto> line = new ArrayList<>(header.size());
            if (row instanceof ScriptObjectMirror mirror && !mirror.isArray() && !mirror.isFunction()) {
                for (String column : header) {
                    line.add(VALUE_COLUMN.equals(column) && !mirror.containsKey(column)
                            ? OqlValueDto.simple(OqlValueType.NULL, "")
                            : convert(session, mirror.get(column), 0));
                }
            } else {
                for (String column : header) {
                    line.add(VALUE_COLUMN.equals(column) ? convert(session, row, 0)
                            : OqlValueDto.simple(OqlValueType.NULL, ""));
                }
            }
            cells.add(line);
        }
        return new OqlResultDto(snapshotId, query, header, cells, cells.size(), rows.truncated(), durationMs);
    }

    /** hat.js wraps heap objects into script objects; the original is behind {@code wrapped-object}. */
    private static Object unwrap(Object value) {
        if (value instanceof ScriptObjectMirror mirror && !mirror.isArray()) {
            try {
                Object wrapped = mirror.getMember(WRAPPED);
                if (wrapped instanceof Instance || wrapped instanceof JavaClass) {
                    return wrapped;
                }
            } catch (RuntimeException ignored) {
                // a plain script object
            }
        }
        return value;
    }

    private OqlValueDto convert(Session session, Object raw, int depth) {
        Object value = unwrap(raw);
        if (value == null || ScriptObjectMirror.isUndefined(value)) {
            return OqlValueDto.simple(OqlValueType.NULL, "null");
        }
        if (value instanceof Instance instance) {
            return instance(session, instance);
        }
        if (value instanceof JavaClass javaClass) {
            String name = JvmTypeNames.toJavaName(javaClass.getName());
            return new OqlValueDto(OqlValueType.CLASS, "class " + name + " (" + javaClass.getInstancesCount()
                    + " instances)", javaClass.getJavaClassId(), name, javaClass.getAllInstancesSize(), null, null, null,
                    (long) javaClass.getInstancesCount());
        }
        if (value instanceof CharSequence text) {
            return OqlValueDto.simple(OqlValueType.STRING, truncate(text.toString()));
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            String text = d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15
                    ? Long.toString((long) d) : number.toString();
            return OqlValueDto.simple(OqlValueType.NUMBER, text);
        }
        if (value instanceof Boolean flag) {
            return OqlValueDto.simple(OqlValueType.BOOLEAN, flag.toString());
        }
        if (value instanceof ScriptObjectMirror mirror) {
            if (mirror.isFunction()) {
                return OqlValueDto.simple(OqlValueType.OTHER, "function");
            }
            if (mirror.isArray()) {
                return list(session, mirror.values().iterator(), (long) mirror.size(), depth);
            }
            return object(session, mirror, depth);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return object(session, copy, depth);
        }
        if (value instanceof Collection<?> collection) {
            return list(session, collection.iterator(), (long) collection.size(), depth);
        }
        if (value instanceof Iterator<?> iterator) {
            return list(session, iterator, null, depth);
        }
        if (value instanceof Enumeration<?> enumeration) {
            return list(session, enumeration.asIterator(), null, depth);
        }
        if (value instanceof Object[] array) {
            return list(session, List.of(array).iterator(), (long) array.length, depth);
        }
        return OqlValueDto.simple(OqlValueType.OTHER, truncate(String.valueOf(value)));
    }

    private OqlValueDto instance(Session session, Instance instance) {
        String className = JvmTypeNames.toJavaName(instance.getJavaClass().getName());
        String text;
        if ("java.lang.String".equals(className)) {
            String content = session.reader.string(instance, MAX_TEXT);
            text = content == null ? "String" : '"' + content + '"';
        } else {
            int length = HeapReader.length(instance);
            String simple = GcRootPathResolver.simpleName(className);
            String shown = length >= 0 && simple.endsWith("[]")
                    ? simple.substring(0, simple.length() - 1) + length + "]" : simple;
            text = shown + "@0x" + Long.toHexString(instance.getInstanceId());
        }
        Long retained = session.engine.getHeap().isRetainedSizeComputed() ? instance.getRetainedSize() : null;
        return new OqlValueDto(OqlValueType.INSTANCE, text, instance.getInstanceId(), className, instance.getSize(),
                retained, null, null, null);
    }

    private OqlValueDto list(Session session, Iterator<?> iterator, Long total, int depth) {
        List<OqlValueDto> items = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        while (iterator.hasNext() && items.size() < MAX_ITEMS) {
            OqlValueDto item = depth < MAX_DEPTH ? convert(session, iterator.next(), depth + 1)
                    : OqlValueDto.simple(OqlValueType.OTHER, "…");
            items.add(item);
            texts.add(item.text());
        }
        boolean more = iterator.hasNext() || (total != null && total > items.size());
        String text = "[" + String.join(", ", texts) + (more ? ", …" : "") + "]";
        return new OqlValueDto(OqlValueType.LIST, truncate(text), null, null, null, null, items, null, total);
    }

    private OqlValueDto object(Session session, Map<String, Object> map, int depth) {
        Map<String, OqlValueDto> fields = new LinkedHashMap<>();
        List<String> texts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (fields.size() >= MAX_ITEMS) {
                break;
            }
            OqlValueDto field = depth < MAX_DEPTH ? convert(session, entry.getValue(), depth + 1)
                    : OqlValueDto.simple(OqlValueType.OTHER, "…");
            fields.put(entry.getKey(), field);
            texts.add(entry.getKey() + ": " + field.text());
        }
        return new OqlValueDto(OqlValueType.OBJECT, truncate("{" + String.join(", ", texts) + "}"), null, null, null,
                null, null, fields, null);
    }

    private static String truncate(String text) {
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
