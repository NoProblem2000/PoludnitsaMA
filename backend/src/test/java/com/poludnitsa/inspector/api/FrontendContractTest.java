package com.poludnitsa.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.poludnitsa.inspector.api.rest.AboutController;
import com.poludnitsa.inspector.api.rest.GcController;
import com.poludnitsa.inspector.api.rest.HeapQueryController;
import com.poludnitsa.inspector.api.rest.InspectionController;
import com.poludnitsa.inspector.api.rest.ProcessControlController;
import com.poludnitsa.inspector.api.rest.ProfilerController;
import com.poludnitsa.inspector.api.rest.ReportController;
import com.poludnitsa.inspector.api.rest.SnapshotController;
import com.poludnitsa.inspector.api.rest.ThreadsController;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

/**
 * Keeps the React client and the backend in step: every interface and string union in {@code frontend/src/api/types.ts}
 * must have exactly the JSON properties / constants Jackson uses for the Java DTO of the same name, and every REST call
 * in {@code endpoints.ts} must hit a controller mapping with the same method, path, query parameters, multipart part
 * and body fields. Skipped when the frontend sources are not next to the backend.
 */
@JsonTest
class FrontendContractTest {

    private static final Path API_DIR = Path.of("..", "frontend", "src", "api");
    private static final String DTO_PACKAGE = "com.poludnitsa.inspector.api.dto.";
    /** TS names whose Java type has another name or package. */
    private static final Map<String, String> JAVA_TYPES = Map.of(
            "SnapshotDataDto", "com.poludnitsa.inspector.snapshot.SnapshotData",
            "SessionReportBundle", "com.poludnitsa.inspector.report.SessionReportBundle",
            "SnapshotReport", "com.poludnitsa.inspector.report.SessionReportBundle$SnapshotReport");
    /** Fields whose TS type is deliberately narrower than the Java one. */
    private static final Set<String> NARROWED_TYPES = Set.of(
            "GrowthWindowDto.windowMinutes", // 1 | 5 | 15: the windows of LeakRadar
            "PageDto.items");                // generic T[]
    /** Client-side unions without a Java enum (the WebSocket client frame is a record with a String action). */
    private static final Set<String> TS_ONLY = Set.of("LiveStreamClientMessage");
    private static final List<Class<?>> CONTROLLERS = List.of(ProcessControlController.class, SnapshotController.class,
            ReportController.class, InspectionController.class, AboutController.class, HeapQueryController.class,
            ProfilerController.class, ThreadsController.class, GcController.class);

    private static final Pattern INTERFACE = Pattern.compile("(?ms)^export interface (\\w+)(?:<\\w+>)? \\{\\n(.*?)^}");
    private static final Pattern FIELD = Pattern.compile("(?m)^  (\\w+)(\\??): (.+);$");
    private static final Pattern UNION = Pattern.compile("(?ms)^export type (\\w+) =(.*?);$");
    private static final Pattern LITERAL = Pattern.compile("'([A-Z0-9_]+)'");
    private static final Pattern CALL = Pattern.compile(
            "http\\.(get|text|post|put|del|download|uploadDownload|upload)(?:<([^>]+(?:<[^>]+>)?[^>]*)>)?\\(\\s*(['`])(.*?)\\3(.*?)\\)[,;]?\\s*$",
            Pattern.MULTILINE | Pattern.DOTALL);

    private static String types;
    private static String endpoints;

    @Autowired
    private ObjectMapper mapper;

    @BeforeAll
    static void readSources() throws IOException {
        Path typesFile = API_DIR.resolve("types.ts");
        assumeThat(Files.exists(typesFile)).as("frontend sources at %s", typesFile.toAbsolutePath()).isTrue();
        types = Files.readString(typesFile, StandardCharsets.UTF_8).replace("\r\n", "\n");
        endpoints = Files.readString(API_DIR.resolve("endpoints.ts"), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    record TsField(String name, boolean optional, String type) {
    }

    private static Map<String, List<TsField>> interfaces() {
        Map<String, List<TsField>> result = new LinkedHashMap<>();
        Matcher m = INTERFACE.matcher(types);
        while (m.find()) {
            List<TsField> fields = new ArrayList<>();
            Matcher f = FIELD.matcher(m.group(2));
            while (f.find()) {
                fields.add(new TsField(f.group(1), !f.group(2).isEmpty(), f.group(3)));
            }
            result.put(m.group(1), fields);
        }
        return result;
    }

    private static Class<?> javaType(String tsName) throws ClassNotFoundException {
        return Class.forName(JAVA_TYPES.getOrDefault(tsName, DTO_PACKAGE + tsName));
    }

    /** JSON property names: what Jackson writes for responses, the record components it reads for requests. */
    private Set<String> jsonProperties(Class<?> type) {
        if (type.getSimpleName().endsWith("RequestDto") && type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        BeanDescription description = mapper.getSerializationConfig().introspect(mapper.constructType(type));
        return description.findProperties().stream().filter(BeanPropertyDefinition::couldSerialize)
                .map(BeanPropertyDefinition::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void everyTypeScriptInterfaceMatchesItsJavaDto() throws Exception {
        Map<String, List<TsField>> interfaces = interfaces();
        assertThat(interfaces).hasSizeGreaterThanOrEqualTo(40);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<TsField>> entry : interfaces.entrySet()) {
            Class<?> type = javaType(entry.getKey());
            Set<String> ts = entry.getValue().stream().map(TsField::name).collect(Collectors.toCollection(TreeSet::new));
            Set<String> java = jsonProperties(type);
            if (!ts.equals(java)) {
                problems.add(entry.getKey() + ": TS " + ts + " vs Java " + java);
                continue;
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    TsField field = entry.getValue().stream().filter(x -> x.name().equals(component.getName()))
                            .findFirst().orElseThrow();
                    if (component.getType().isPrimitive() && (field.type().contains("null") || field.optional())) {
                        problems.add(entry.getKey() + "." + field.name() + " is primitive in Java but nullable in TS");
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void everyStringUnionMatchesItsJavaEnum() throws Exception {
        Matcher m = UNION.matcher(types);
        int checked = 0;
        List<String> problems = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            Set<String> literals = new TreeSet<>();
            Matcher literal = LITERAL.matcher(m.group(2));
            while (literal.find()) {
                literals.add(literal.group(1));
            }
            if (TS_ONLY.contains(name) || literals.isEmpty()) {
                continue;
            }
            Class<?> type = javaType(name);
            Set<String> constants = Arrays.stream(type.getEnumConstants()).map(c -> ((Enum<?>) c).name())
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!literals.equals(constants)) {
                problems.add(name + ": TS " + literals + " vs Java " + constants);
            }
            checked++;
        }
        assertThat(problems).isEmpty();
        assertThat(checked).isGreaterThanOrEqualTo(13);
    }

    /**
     * Field types, not only names: the TS type of every field must be what Jackson writes for its Java type (numbers,
     * strings incl. {@code Instant}, booleans, arrays of collections, {@code Record} of maps, DTO / enum names).
     * {@code | null} is ignored here, nullability of primitives is checked above.
     */
    @Test
    void everyFieldHasTheTypeOfItsJavaProperty() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, List<TsField>> entry : interfaces().entrySet()) {
            Map<String, JavaType> java = jsonPropertyTypes(javaType(entry.getKey()));
            for (TsField field : entry.getValue()) {
                JavaType type = java.get(field.name());
                if (type == null || NARROWED_TYPES.contains(entry.getKey() + "." + field.name())) {
                    continue; // missing ones are reported by the name check
                }
                String expected = tsTypeOf(type);
                String actual = normalizeTs(field.type());
                if (!expected.equals(actual)) {
                    problems.add(entry.getKey() + "." + field.name() + ": TS " + field.type() + " vs Java " + expected);
                }
                checked++;
            }
        }
        assertThat(problems).isEmpty();
        assertThat(checked).isGreaterThanOrEqualTo(300);
    }

    private Map<String, JavaType> jsonPropertyTypes(Class<?> type) {
        Map<String, JavaType> result = new LinkedHashMap<>();
        if (type.getSimpleName().endsWith("RequestDto") && type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                result.put(component.getName(), mapper.constructType(component.getGenericType()));
            }
            return result;
        }
        BeanDescription description = mapper.getSerializationConfig().introspect(mapper.constructType(type));
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (property.couldSerialize()) {
                result.put(property.getName(), property.getPrimaryType());
            }
        }
        return result;
    }

    private static String tsTypeOf(JavaType type) {
        Class<?> raw = type.getRawClass();
        if (raw == byte[].class || raw == String.class || raw == char.class || raw == Character.class
                || java.time.temporal.Temporal.class.isAssignableFrom(raw)) {
            return "string"; // byte[] is base64, Instant ISO-8601 (write-dates-as-timestamps: false)
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return "boolean";
        }
        if (raw.isPrimitive() || Number.class.isAssignableFrom(raw)) {
            return "number";
        }
        if (type.isArrayType() || type.isCollectionLikeType()) {
            String element = tsTypeOf(type.getContentType());
            return element.matches("[\\w.]+(\\[])*") ? element + "[]" : "(" + element + ")[]";
        }
        if (type.isMapLikeType()) {
            return "Record<" + tsTypeOf(type.getKeyType()) + ", " + tsTypeOf(type.getContentType()) + ">";
        }
        String name = JAVA_TYPES.entrySet().stream().filter(e -> e.getValue().equals(raw.getName()))
                .map(Map.Entry::getKey).findFirst().orElse(raw.getSimpleName());
        if (type.containedTypeCount() > 0 && !type.containedType(0).getRawClass().equals(Object.class)) {
            List<String> arguments = new ArrayList<>();
            for (int i = 0; i < type.containedTypeCount(); i++) {
                arguments.add(tsTypeOf(type.containedType(i)));
            }
            return name + "<" + String.join(", ", arguments) + ">"; // PageDto<ClassStatsDto>
        }
        return name;
    }

    /** Drops {@code | null} and spelling variants: {@code Partial<Record<…>>}, {@code Array<T>}, {@code (T)[]}. */
    private static String normalizeTs(String ts) {
        // undefined: a 204 without body (http.ts resolves it to undefined)
        String s = ts.replace(" | null", "").replace("null | ", "").replace(" | undefined", "").trim();
        s = s.replaceAll("^Partial<(Record<.*>)>$", "$1");
        s = s.replaceAll("Array<([\\w.]+)>", "$1[]");
        s = s.replaceAll("\\(([\\w.]+(?:\\[])*)\\)\\[]", "$1[]");
        return s;
    }

    record Mapping(String method, String path, Method handler) {
    }

    private static String normalize(String path) {
        return path.replaceAll("\\$\\{[^}]+}", "{}").replaceAll("\\{\\w+}", "{}");
    }

    private static List<Mapping> backendMappings() {
        List<Mapping> result = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping base = controller.getAnnotation(RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                String[][] found = {
                    pathOf(method.getAnnotation(GetMapping.class), "GET"),
                    pathOf(method.getAnnotation(PostMapping.class), "POST"),
                    pathOf(method.getAnnotation(PutMapping.class), "PUT"),
                    pathOf(method.getAnnotation(DeleteMapping.class), "DELETE"),
                };
                for (String[] verbAndPath : found) {
                    if (verbAndPath != null) {
                        result.add(new Mapping(verbAndPath[0], normalize(prefix + verbAndPath[1]), method));
                    }
                }
            }
        }
        return result;
    }

    private static String[] pathOf(Annotation annotation, String verb) {
        if (annotation == null) {
            return null;
        }
        String[] value;
        String[] path;
        if (annotation instanceof GetMapping a) {
            value = a.value();
            path = a.path();
        } else if (annotation instanceof PostMapping a) {
            value = a.value();
            path = a.path();
        } else if (annotation instanceof PutMapping a) {
            value = a.value();
            path = a.path();
        } else {
            DeleteMapping a = (DeleteMapping) annotation;
            value = a.value();
            path = a.path();
        }
        String[] paths = value.length > 0 ? value : path;
        return new String[] {verb, paths.length > 0 ? paths[0] : ""};
    }

    private static String paramName(Parameter parameter, String annotated) {
        return annotated == null || annotated.isEmpty() ? parameter.getName() : annotated;
    }

    @Test
    void everyRestCallOfTheClientHitsAControllerMapping() {
        List<Mapping> mappings = backendMappings();
        Matcher call = CALL.matcher(endpoints);
        // query parameters that endpoints.ts builds with URLSearchParams
        Set<String> builtQuery = new LinkedHashSet<>();
        Matcher set = Pattern.compile("query\\.set\\('(\\w+)'").matcher(endpoints);
        while (set.find()) {
            builtQuery.add(set.group(1));
        }
        // the call tree queries of the profiler build theirs in windowQuery(metric, window, { extra })
        Set<String> windowQuery = new LinkedHashSet<>(List.of("metric"));
        Matcher windowSet = Pattern.compile("params\\.set\\('(\\w+)'").matcher(endpoints);
        while (windowSet.find()) {
            windowQuery.add(windowSet.group(1));
        }

        List<String> problems = new ArrayList<>();
        int checked = 0;
        while (call.find()) {
            String verb = switch (call.group(1)) {
                case "get", "text" -> "GET";
                case "put" -> "PUT";
                case "del" -> "DELETE";
                default -> "POST";
            };
            String url = call.group(4);
            String rest = call.group(5);
            int q = url.indexOf('?');
            String path = normalize(q < 0 ? url : url.substring(0, q));
            Set<String> query = new LinkedHashSet<>();
            if (q >= 0) {
                Matcher param = Pattern.compile("[?&](\\w+)=").matcher(url.substring(q) + rest);
                while (param.find()) {
                    query.add(param.group(1));
                }
            }
            if (url.contains("query.toString()")) {
                query.addAll(builtQuery);
            }
            Matcher window = Pattern.compile("windowQuery\\([^{)]*(?:\\{([^}]*)})?").matcher(url);
            if (window.find()) {
                query.addAll(windowQuery);
                for (String key : window.group(1) == null ? new String[0] : window.group(1).split(",")) {
                    if (!key.isBlank()) {
                        query.add(key.split(":")[0].trim());
                    }
                }
            }
            Mapping mapping = mappings.stream().filter(x -> x.method().equals(verb) && x.path().equals(path))
                    .findFirst().orElse(null);
            checked++;
            if (mapping == null) {
                problems.add(verb + " " + path + ": no controller mapping");
                continue;
            }
            Set<String> params = new LinkedHashSet<>();
            Set<String> parts = new LinkedHashSet<>();
            Class<?> body = null;
            for (Parameter parameter : mapping.handler().getParameters()) {
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                RequestPart requestPart = parameter.getAnnotation(RequestPart.class);
                if (requestParam != null) {
                    params.add(paramName(parameter, requestParam.value().isEmpty() ? requestParam.name() : requestParam.value()));
                }
                if (requestPart != null) {
                    parts.add(paramName(parameter, requestPart.value().isEmpty() ? requestPart.name() : requestPart.value()));
                }
                if (parameter.isAnnotationPresent(RequestBody.class)) {
                    body = parameter.getType();
                }
            }
            for (String name : query) {
                if (!params.contains(name)) {
                    problems.add(verb + " " + path + ": unknown query parameter '" + name + "', backend has " + params);
                }
            }
            if (call.group(1).startsWith("upload")) {
                Matcher part = Pattern.compile("^\\s*,\\s*'(\\w+)'").matcher(rest);
                if (!part.find() || !parts.contains(part.group(1))) {
                    problems.add(verb + " " + path + ": multipart part does not match " + parts);
                }
            }
            // the response type the client expects must be what the handler returns
            if (call.group(2) != null) {
                String expected = responseTsType(mapping.handler());
                String actual = normalizeTs(call.group(2));
                if (expected != null && !expected.equals(actual)) {
                    problems.add(verb + " " + path + ": client expects " + call.group(2) + ", handler returns " + expected);
                }
            }
            Matcher literal = Pattern.compile(",\\s*\\{([^}]*)}\\s*$").matcher(rest);
            if (literal.find() && body != null && body.isRecord()) {
                Set<String> components = Arrays.stream(body.getRecordComponents()).map(RecordComponent::getName)
                        .collect(Collectors.toSet());
                for (String key : literal.group(1).split(",")) {
                    String name = key.split(":")[0].trim();
                    if (!name.isEmpty() && !components.contains(name)) {
                        problems.add(verb + " " + path + ": body field '" + name + "' not in " + body.getSimpleName());
                    }
                }
            }
        }
        assertThat(problems).isEmpty();
        assertThat(checked).isGreaterThanOrEqualTo(20);
    }

    /** TS type of a handler's JSON response: ResponseEntity unwrapped, void for no body, null when not JSON. */
    private String responseTsType(Method handler) {
        JavaType type = mapper.constructType(handler.getGenericReturnType());
        if (type.getRawClass() == org.springframework.http.ResponseEntity.class) {
            type = type.containedTypeOrUnknown(0);
        }
        Class<?> raw = type.getRawClass();
        if (raw == void.class || raw == Void.class) {
            return "void";
        }
        if (raw == Object.class || raw.getName().contains("StreamingResponseBody") || raw == String.class
                || raw == byte[].class || org.springframework.core.io.Resource.class.isAssignableFrom(raw)) {
            return null; // downloads and text: not a typed JSON body
        }
        return tsTypeOf(type);
    }

    @Test
    void webSocketEndpointAndActionsMatch() throws IOException {
        String client = Files.readString(API_DIR.resolve("liveSocket.ts"), StandardCharsets.UTF_8);
        Path source = Path.of("src", "main", "java", "com", "poludnitsa", "inspector");
        String config = Files.readString(source.resolve(Path.of("config", "WebSocketConfig.java")), StandardCharsets.UTF_8);
        String handler = Files.readString(source.resolve(Path.of("api", "ws", "LiveMetricsWebSocketHandler.java")),
                StandardCharsets.UTF_8);

        assertThat(client).contains("/ws/live");
        assertThat(config).contains("\"/ws/live\"");
        Matcher action = Pattern.compile("action: '(\\w+)'").matcher(client);
        Set<String> actions = new TreeSet<>();
        while (action.find()) {
            actions.add(action.group(1));
        }
        assertThat(actions).containsExactly("ping", "subscribe", "unsubscribe");
        for (String name : actions) {
            assertThat(handler).contains("case \"" + name + "\"");
        }
    }
}
