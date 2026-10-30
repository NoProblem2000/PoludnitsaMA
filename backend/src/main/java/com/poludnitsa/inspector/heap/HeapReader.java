package com.poludnitsa.inspector.heap;

import com.poludnitsa.inspector.common.JvmTypeNames;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.netbeans.lib.profiler.heap.ArrayItemValue;
import org.netbeans.lib.profiler.heap.Field;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.ObjectArrayInstance;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;
import org.netbeans.lib.profiler.heap.PrimitiveArrayInstance;
import org.netbeans.lib.profiler.heap.Value;

/**
 * Typed access to objects of an open NetBeans {@link Heap}: fields, strings, byte arrays, the JDK collections
 * (without running any of their code) and who references whom.
 *
 * <p>Every method tolerates missing fields and unexpected shapes and answers {@code null} / {@code -1} / nothing
 * instead of throwing: inspections run against dumps of arbitrary library versions.
 */
public final class HeapReader {

    /** Visitor of map entries; return {@code false} to stop. {@code hash} is the stored node hash or 0. */
    @FunctionalInterface
    public interface EntryVisitor {
        boolean visit(Instance key, Instance value, long hash);
    }

    /** One incoming reference: {@code holder.field = target}, {@code static field} or {@code holder[index]}. */
    public record Referrer(Instance holder, String holderClass, String field, boolean staticField, int index) {

        /** {@code OrderService.cache}, {@code static CACHE in com.acme.OrderService}, {@code Object[17]}. */
        public String describe() {
            if (staticField) {
                return "static " + field + " in " + holderClass;
            }
            String simple = GcRootPathResolver.simpleName(holderClass);
            if (index >= 0) {
                return (simple.endsWith("[]") ? simple.substring(0, simple.length() - 2) : simple) + "[" + index + "]";
            }
            return field == null ? simple : simple + "." + field;
        }
    }

    /** The first meaningful holder above JDK collection plumbing. */
    public record Owner(String className, String field, boolean staticField, Instance holder, String via) {

        public String describe() {
            String base = staticField ? "static " + field + " in " + className
                    : field == null ? className : className + "." + field;
            return via == null ? base : base + " (через " + via + ")";
        }
    }

    private enum Kind {
        HASH_MAP, CONCURRENT_HASH_MAP, HASHTABLE, WEAK_HASH_MAP, IDENTITY_HASH_MAP, TREE_MAP, CONCURRENT_SKIP_LIST_MAP,
        HASH_SET, TREE_SET, ARRAY_LIST, VECTOR, COPY_ON_WRITE_LIST, COPY_ON_WRITE_SET, ARRAY_DEQUE, PRIORITY_QUEUE,
        LINKED_LIST, LINKED_BLOCKING_QUEUE, LINKED_BLOCKING_DEQUE, CONCURRENT_LINKED_QUEUE, ARRAY_BLOCKING_QUEUE,
        MAP_WRAPPER, COLLECTION_WRAPPER
    }

    private static final Map<String, Kind> KINDS = Map.ofEntries(
            Map.entry("java.util.HashMap", Kind.HASH_MAP),
            Map.entry("java.util.LinkedHashMap", Kind.HASH_MAP),
            Map.entry("java.util.concurrent.ConcurrentHashMap", Kind.CONCURRENT_HASH_MAP),
            Map.entry("java.util.Hashtable", Kind.HASHTABLE),
            Map.entry("java.util.WeakHashMap", Kind.WEAK_HASH_MAP),
            Map.entry("java.util.IdentityHashMap", Kind.IDENTITY_HASH_MAP),
            Map.entry("java.util.TreeMap", Kind.TREE_MAP),
            Map.entry("java.util.concurrent.ConcurrentSkipListMap", Kind.CONCURRENT_SKIP_LIST_MAP),
            Map.entry("java.util.HashSet", Kind.HASH_SET),
            Map.entry("java.util.TreeSet", Kind.TREE_SET),
            Map.entry("java.util.concurrent.ConcurrentSkipListSet", Kind.TREE_SET),
            Map.entry("java.util.ArrayList", Kind.ARRAY_LIST),
            Map.entry("java.util.Vector", Kind.VECTOR),
            Map.entry("java.util.concurrent.CopyOnWriteArrayList", Kind.COPY_ON_WRITE_LIST),
            Map.entry("java.util.concurrent.CopyOnWriteArraySet", Kind.COPY_ON_WRITE_SET),
            Map.entry("java.util.ArrayDeque", Kind.ARRAY_DEQUE),
            Map.entry("java.util.PriorityQueue", Kind.PRIORITY_QUEUE),
            Map.entry("java.util.concurrent.PriorityBlockingQueue", Kind.PRIORITY_QUEUE),
            Map.entry("java.util.LinkedList", Kind.LINKED_LIST),
            Map.entry("java.util.concurrent.LinkedBlockingQueue", Kind.LINKED_BLOCKING_QUEUE),
            Map.entry("java.util.concurrent.LinkedBlockingDeque", Kind.LINKED_BLOCKING_DEQUE),
            Map.entry("java.util.concurrent.ConcurrentLinkedQueue", Kind.CONCURRENT_LINKED_QUEUE),
            Map.entry("java.util.concurrent.ArrayBlockingQueue", Kind.ARRAY_BLOCKING_QUEUE),
            Map.entry("java.util.Collections$SynchronizedMap", Kind.MAP_WRAPPER),
            Map.entry("java.util.Collections$UnmodifiableMap", Kind.MAP_WRAPPER),
            Map.entry("java.util.Collections$SynchronizedCollection", Kind.COLLECTION_WRAPPER),
            Map.entry("java.util.Collections$UnmodifiableCollection", Kind.COLLECTION_WRAPPER));

    private static final Method GET_BYTES = findGetBytes();

    private final Heap heap;
    private final boolean bigEndian;

    public HeapReader(Heap heap) {
        this.heap = heap;
        Properties properties = null;
        try {
            properties = heap.getSystemProperties();
        } catch (RuntimeException ignored) {
            // older dumps without java.lang.System properties
        }
        this.bigEndian = properties != null && "big".equalsIgnoreCase(properties.getProperty("sun.cpu.endian"));
    }

    public Heap heap() {
        return heap;
    }

    // ---------------------------------------------------------------- classes and instances

    /** Every class definition with exactly this name (one per defining class loader). */
    public List<JavaClass> classesNamed(String name) {
        List<JavaClass> result = new ArrayList<>(1);
        Collection<?> matches = heap.getJavaClassesByRegExp(Pattern.quote(name));
        if (matches != null) {
            for (Object match : matches) {
                result.add((JavaClass) match);
            }
        }
        return result;
    }

    /** The classes with this name and all their subclasses. */
    public List<JavaClass> withSubclasses(String name) {
        List<JavaClass> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<JavaClass> queue = new ArrayDeque<>(classesNamed(name));
        while (!queue.isEmpty()) {
            JavaClass current = queue.poll();
            if (!seen.add(current.getJavaClassId())) {
                continue;
            }
            result.add(current);
            Collection<?> subclasses = current.getSubClasses();
            if (subclasses != null) {
                for (Object subclass : subclasses) {
                    queue.add((JavaClass) subclass);
                }
            }
        }
        return result;
    }

    /** Lazily iterates the instances of the given classes. */
    public static Iterable<Instance> instancesOf(List<JavaClass> classes) {
        return () -> new Iterator<>() {
            private int index;
            private Iterator<?> current;

            @Override
            public boolean hasNext() {
                while ((current == null || !current.hasNext()) && index < classes.size()) {
                    current = classes.get(index++).getInstancesIterator();
                }
                return current != null && current.hasNext();
            }

            @Override
            public Instance next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return (Instance) current.next();
            }
        };
    }

    public static int instanceCount(List<JavaClass> classes) {
        int count = 0;
        for (JavaClass javaClass : classes) {
            count += javaClass.getInstancesCount();
        }
        return count;
    }

    public static String className(Instance instance) {
        JavaClass javaClass = instance == null ? null : instance.getJavaClass();
        return javaClass == null ? "?" : JvmTypeNames.toJavaName(javaClass.getName());
    }

    /** For class objects (holders of static fields): the class they represent. */
    public JavaClass representedClass(Instance instance) {
        JavaClass javaClass = instance.getJavaClass();
        if (javaClass == null || !"java.lang.Class".equals(javaClass.getName())) {
            return null;
        }
        return heap.getJavaClassByID(instance.getInstanceId());
    }

    public static boolean isInstanceOf(Instance instance, String className) {
        for (JavaClass c = instance == null ? null : instance.getJavaClass(); c != null; c = c.getSuperClass()) {
            if (className.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- fields

    public static Object field(Instance instance, String name) {
        if (instance == null) {
            return null;
        }
        try {
            return instance.getValueOfField(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Instance objectField(Instance instance, String name) {
        return field(instance, name) instanceof Instance value ? value : null;
    }

    public static long longField(Instance instance, String name, long fallback) {
        Object value = field(instance, name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        if (value instanceof Character character) {
            return character;
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean booleanField(Instance instance, String name) {
        Object value = field(instance, name);
        return value instanceof Boolean flag ? flag : "true".equals(String.valueOf(value));
    }

    /** Non-static object fields with a non-null value, in declaration order (superclass fields included). */
    public static List<ObjectFieldValue> objectFields(Instance instance) {
        List<ObjectFieldValue> result = new ArrayList<>();
        if (instance == null) {
            return result;
        }
        for (Object value : instance.getFieldValues()) {
            if (value instanceof ObjectFieldValue field && !field.getField().isStatic() && field.getInstance() != null) {
                result.add(field);
            }
        }
        return result;
    }

    public static boolean declaresField(JavaClass javaClass, String name) {
        for (JavaClass c = javaClass; c != null; c = c.getSuperClass()) {
            for (Object field : c.getFields()) {
                if (name.equals(((Field) field).getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code name} of an enum constant, or {@code null}. */
    public String enumName(Instance constant) {
        return constant == null ? null : string(objectField(constant, "name"), 64);
    }

    // ---------------------------------------------------------------- strings and arrays

    /** Decodes a {@code java.lang.String} (Java 9+ byte[] + coder, or Java 8 char[]), at most {@code maxChars}. */
    public String string(Instance string, int maxChars) {
        if (string == null || !"java.lang.String".equals(className(string))) {
            return null;
        }
        Instance value = objectField(string, "value");
        if (!(value instanceof PrimitiveArrayInstance array)) {
            return null;
        }
        if ("byte[]".equals(className(array))) {
            boolean utf16 = longField(string, "coder", 0) == 1;
            int take = (int) Math.min(array.getLength(), utf16 ? 2L * maxChars : maxChars);
            byte[] bytes = bytes(array, take);
            if (!utf16) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
            return new String(bytes, 0, bytes.length & ~1,
                    bigEndian ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE);
        }
        List<?> chars = array.getValues();
        int take = Math.min(chars.size(), maxChars);
        StringBuilder text = new StringBuilder(take);
        for (int i = 0; i < take; i++) {
            String element = String.valueOf(chars.get(i));
            text.append(element.isEmpty() ? '?' : element.charAt(0));
        }
        return text.toString();
    }

    /** Raw content of a {@code byte[]}, at most {@code max} bytes. */
    public byte[] bytes(PrimitiveArrayInstance array, int max) {
        int length = Math.max(0, Math.min(array.getLength(), max));
        if (GET_BYTES != null && GET_BYTES.getDeclaringClass().isInstance(array)) {
            try {
                return (byte[]) GET_BYTES.invoke(array, 0, length);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // fall back to the public, slower API
            }
        }
        List<?> values = array.getValues();
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = Byte.parseByte(String.valueOf(values.get(i)));
        }
        return result;
    }

    public static int length(Instance array) {
        if (array instanceof ObjectArrayInstance objects) {
            return objects.getLength();
        }
        if (array instanceof PrimitiveArrayInstance primitives) {
            return primitives.getLength();
        }
        return -1;
    }

    /** Non-null elements of an object array, scanning at most {@code maxSlots} slots. */
    public static List<Instance> elements(Instance array, int maxSlots) {
        List<Instance> result = new ArrayList<>();
        if (array instanceof ObjectArrayInstance objects) {
            List<?> values = objects.getValues();
            int slots = Math.min(values.size(), maxSlots);
            for (int i = 0; i < slots; i++) {
                if (values.get(i) instanceof Instance element) {
                    result.add(element);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- collections

    public static boolean isCollection(Instance instance) {
        return kind(instance) != null;
    }

    /** Element count of a JDK collection or map (custom subclasses included); {@code -1} if unknown. */
    public long size(Instance collection) {
        Kind kind = kind(collection);
        if (kind == null) {
            return -1;
        }
        return switch (kind) {
            case HASH_MAP, WEAK_HASH_MAP, IDENTITY_HASH_MAP, TREE_MAP, ARRAY_LIST, LINKED_LIST, PRIORITY_QUEUE ->
                    longField(collection, "size", -1);
            case HASHTABLE, ARRAY_BLOCKING_QUEUE, LINKED_BLOCKING_DEQUE -> longField(collection, "count", -1);
            case VECTOR -> longField(collection, "elementCount", -1);
            case LINKED_BLOCKING_QUEUE -> longField(objectField(collection, "count"), "value", -1);
            case CONCURRENT_HASH_MAP -> concurrentHashMapSize(collection);
            case HASH_SET -> size(objectField(collection, "map"));
            case TREE_SET -> size(objectField(collection, "m"));
            case COPY_ON_WRITE_LIST -> length(objectField(collection, "array"));
            case COPY_ON_WRITE_SET -> size(objectField(collection, "al"));
            case ARRAY_DEQUE -> arrayDequeSize(collection);
            case MAP_WRAPPER -> size(objectField(collection, "m"));
            case COLLECTION_WRAPPER -> size(objectField(collection, "c"));
            case CONCURRENT_SKIP_LIST_MAP, CONCURRENT_LINKED_QUEUE -> countByWalking(collection);
        };
    }

    /** Visits up to {@code limit} entries of a JDK map (or the elements of a set as keys). */
    public void forEachMapEntry(Instance map, int limit, EntryVisitor visitor) {
        Kind kind = kind(map);
        if (kind == null) {
            return;
        }
        int[] remaining = {limit};
        EntryVisitor limited = (key, value, hash) -> remaining[0]-- > 0 && visitor.visit(key, value, hash);
        switch (kind) {
            case HASH_MAP, HASHTABLE, WEAK_HASH_MAP, CONCURRENT_HASH_MAP ->
                    hashTable(objectField(map, "table"), kind, limited);
            case IDENTITY_HASH_MAP -> identityTable(objectField(map, "table"), limited);
            case TREE_MAP -> treeMap(objectField(map, "root"), limited);
            case HASH_SET -> forEachMapEntry(objectField(map, "map"), limit, visitor);
            case TREE_SET -> forEachMapEntry(objectField(map, "m"), limit, visitor);
            case MAP_WRAPPER -> forEachMapEntry(objectField(map, "m"), limit, visitor);
            default -> {
                // not a map
            }
        }
    }

    /** Visits up to {@code limit} elements of a JDK list, queue or set; return {@code false} to stop. */
    public void forEachElement(Instance collection, int limit, Predicate<Instance> visitor) {
        Kind kind = kind(collection);
        if (kind == null) {
            return;
        }
        switch (kind) {
            case ARRAY_LIST, VECTOR, PRIORITY_QUEUE -> {
                long size = size(collection);
                Instance array = objectField(collection, kind == Kind.PRIORITY_QUEUE ? "queue" : "elementData");
                visitAll(elements(array, (int) Math.min(Math.max(size, 0), limit)), visitor);
            }
            case COPY_ON_WRITE_LIST -> visitAll(elements(objectField(collection, "array"), limit), visitor);
            case COPY_ON_WRITE_SET -> forEachElement(objectField(collection, "al"), limit, visitor);
            case ARRAY_DEQUE -> visitAll(elements(objectField(collection, "elements"), Integer.MAX_VALUE).stream()
                    .limit(limit).toList(), visitor);
            case ARRAY_BLOCKING_QUEUE -> visitAll(elements(objectField(collection, "items"), Integer.MAX_VALUE)
                    .stream().limit(limit).toList(), visitor);
            case LINKED_LIST -> linked(objectField(collection, "first"), "item", limit, visitor);
            case LINKED_BLOCKING_QUEUE, CONCURRENT_LINKED_QUEUE ->
                    linked(objectField(collection, "head"), "item", limit, visitor);
            case LINKED_BLOCKING_DEQUE -> linked(objectField(collection, "first"), "item", limit, visitor);
            case HASH_SET, TREE_SET -> forEachMapEntry(collection, limit, (key, value, hash) -> visitor.test(key));
            case COLLECTION_WRAPPER -> forEachElement(objectField(collection, "c"), limit, visitor);
            default -> {
                // maps: visit values
                forEachMapEntry(collection, limit, (key, value, hash) -> value == null || visitor.test(value));
            }
        }
    }

    private static Kind kind(Instance instance) {
        if (instance == null) {
            return null;
        }
        for (JavaClass c = instance.getJavaClass(); c != null; c = c.getSuperClass()) {
            Kind kind = KINDS.get(c.getName());
            if (kind != null) {
                return kind;
            }
            String name = c.getName();
            if (name.startsWith("java.util.Collections$Synchronized")
                    || name.startsWith("java.util.Collections$Unmodifiable")) {
                return name.contains("Map") ? Kind.MAP_WRAPPER : Kind.COLLECTION_WRAPPER;
            }
        }
        return null;
    }

    private long concurrentHashMapSize(Instance map) {
        long size = longField(map, "baseCount", 0);
        for (Instance cell : elements(objectField(map, "counterCells"), 1024)) {
            size += longField(cell, "value", 0);
        }
        return size;
    }

    private static long arrayDequeSize(Instance deque) {
        int capacity = length(objectField(deque, "elements"));
        if (capacity <= 0) {
            return 0;
        }
        long head = longField(deque, "head", 0);
        long tail = longField(deque, "tail", 0);
        return Math.floorMod(tail - head, capacity);
    }

    private long countByWalking(Instance collection) {
        long[] count = {0};
        if (kind(collection) == Kind.CONCURRENT_SKIP_LIST_MAP) {
            Instance node = objectField(objectField(objectField(collection, "head"), "node"), "next");
            for (int guard = 0; node != null && guard < 10_000_000; guard++) {
                if (objectField(node, "val") != null) {
                    count[0]++;
                }
                node = objectField(node, "next");
            }
            return count[0];
        }
        linked(objectField(collection, "head"), "item", Integer.MAX_VALUE, item -> {
            count[0]++;
            return true;
        });
        return count[0];
    }

    private static void hashTable(Instance table, Kind kind, EntryVisitor visitor) {
        String valueField = kind == Kind.CONCURRENT_HASH_MAP ? "val" : "value";
        String keyField = kind == Kind.WEAK_HASH_MAP ? "referent" : "key";
        if (!(table instanceof ObjectArrayInstance buckets)) {
            return;
        }
        for (Object slot : buckets.getValues()) {
            Instance node = slot instanceof Instance bucket ? bucket : null;
            if (node != null && className(node).endsWith("$TreeBin")) {
                node = objectField(node, "first");
            }
            for (int guard = 0; node != null && guard < 1_000_000; guard++) {
                if (className(node).endsWith("$ForwardingNode")) {
                    break;
                }
                Instance key = objectField(node, keyField);
                if (!visitor.visit(key, objectField(node, valueField), longField(node, "hash", 0))) {
                    return;
                }
                node = objectField(node, "next");
            }
        }
    }

    private static void identityTable(Instance table, EntryVisitor visitor) {
        if (!(table instanceof ObjectArrayInstance slots)) {
            return;
        }
        List<?> values = slots.getValues();
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (values.get(i) instanceof Instance key && !visitor.visit(key,
                    values.get(i + 1) instanceof Instance value ? value : null, 0)) {
                return;
            }
        }
    }

    private static void treeMap(Instance root, EntryVisitor visitor) {
        Deque<Instance> stack = new ArrayDeque<>();
        if (root != null) {
            stack.push(root);
        }
        int guard = 0;
        while (!stack.isEmpty() && guard++ < 10_000_000) {
            Instance node = stack.pop();
            if (!visitor.visit(objectField(node, "key"), objectField(node, "value"), 0)) {
                return;
            }
            Instance left = objectField(node, "left");
            Instance right = objectField(node, "right");
            if (right != null) {
                stack.push(right);
            }
            if (left != null) {
                stack.push(left);
            }
        }
    }

    private static void linked(Instance node, String itemField, int limit, Predicate<Instance> visitor) {
        Set<Long> seen = new HashSet<>();
        int visited = 0;
        while (node != null && visited < limit && seen.add(node.getInstanceId())) {
            Instance item = objectField(node, itemField);
            if (item != null) {
                visited++;
                if (!visitor.test(item)) {
                    return;
                }
            }
            node = objectField(node, "next");
        }
    }

    private static void visitAll(List<Instance> items, Predicate<Instance> visitor) {
        for (Instance item : items) {
            if (!visitor.test(item)) {
                return;
            }
        }
    }

    // ---------------------------------------------------------------- references

    /** Incoming references of {@code target}, at most {@code limit}. */
    public List<Referrer> referrers(Instance target, int limit) {
        List<Referrer> result = new ArrayList<>();
        if (target == null) {
            return result;
        }
        for (Object reference : target.getReferences()) {
            if (result.size() >= limit) {
                break;
            }
            Value value = (Value) reference;
            Instance holder = value.getDefiningInstance();
            if (holder == null) {
                continue;
            }
            if (value instanceof ArrayItemValue item) {
                result.add(new Referrer(holder, className(holder), null, false, item.getIndex()));
            } else if (value instanceof ObjectFieldValue field) {
                boolean isStatic = field.getField().isStatic();
                JavaClass represented = isStatic ? representedClass(holder) : null;
                String holderClass = represented != null ? JvmTypeNames.toJavaName(represented.getName())
                        : className(holder);
                result.add(new Referrer(holder, holderClass, field.getField().getName(), isStatic, -1));
            }
        }
        return result;
    }

    /**
     * Walks up incoming references through JDK collection internals, arrays and references to the first "real"
     * holder: an application or framework object, or a static field.
     */
    public Owner owner(Instance target) {
        Deque<Instance> queue = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        queue.add(target);
        seen.add(target.getInstanceId());
        String via = null;
        for (int depth = 0; depth < 8 && !queue.isEmpty(); depth++) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                Instance current = queue.poll();
                for (Referrer referrer : referrers(current, 32)) {
                    if (referrer.staticField() || !isPlumbing(referrer.holderClass())) {
                        return new Owner(referrer.holderClass(), referrer.field(), referrer.staticField(),
                                referrer.holder(), depth == 0 ? null : via);
                    }
                    if (seen.add(referrer.holder().getInstanceId())) {
                        if (via == null && isCollection(referrer.holder())) {
                            via = GcRootPathResolver.simpleName(referrer.holderClass());
                        }
                        queue.add(referrer.holder());
                    }
                }
            }
        }
        return null;
    }

    /** JDK containers whose only job is to hold other objects. */
    public static boolean isPlumbing(String className) {
        return className.endsWith("[]")
                || className.startsWith("java.util.")
                || className.startsWith("java.lang.ref.")
                || className.startsWith("java.lang.ThreadLocal$");
    }

    private static Method findGetBytes() {
        try {
            Method method = Class.forName("org.netbeans.lib.profiler.heap.PrimitiveArrayDump")
                    .getDeclaredMethod("getBytes", int.class, int.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
