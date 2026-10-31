package com.poludnitsa.inspector.it.scenario;

import io.undertow.server.session.InMemorySessionManager;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import org.hibernate.internal.SessionImpl;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleLoader;
import org.jboss.weld.contexts.CreationalContextImpl;
import org.jboss.weld.contexts.SerializableContextualInstanceImpl;
import org.jboss.weld.contexts.unbound.ApplicationContextImpl;

/**
 * Child JVM reproducing the WildFly / Jakarta / JVM leak patterns the inspections must diagnose: a redeployed
 * deployment class loader kept by a pool thread, business objects in ThreadLocals of {@code default task-*}
 * threads, a bloated Hibernate session, 12 000 HTTP sessions, a CDI bean with a growing cache and thousands of
 * {@code @Dependent} instances, a map with keys lacking {@code equals/hashCode}, duplicated strings, eager empty
 * collections and boxed {@code Long}s in 80 000 customers, and a payload held only by a {@code SoftReference}.
 */
public final class ScenarioTarget {

    /** "Server" side: the objects WildFly itself would keep. */
    static final class Server {
        static final ModuleLoader MODULE_LOADER = new ModuleLoader();
        static final List<ClassLoader> ACTIVE_LOADERS = new ArrayList<>();
        static InMemorySessionManager sessionManager;
        static ApplicationContextImpl applicationContext;
        static SessionImpl batchSession;
    }

    public static final class UserContext {
        final String user;
        final byte[] permissions = new byte[600 * 1024];

        UserContext(String user) {
            this.user = user;
        }
    }

    public static final class RequestContext {
        static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

        private RequestContext() {
        }
    }

    public static final class OrderEntity {
        final long id;
        final String number;

        OrderEntity(long id) {
            this.id = id;
            this.number = "ORD-" + id;
        }
    }

    public static final class CartDto {
        final byte[] items = new byte[256];
    }

    public static final class ReportBuilder {
        final byte[] buffer = new byte[64];
    }

    public static final class ReportService {
        final Map<Long, String> cache = new HashMap<>();
    }

    /** Key without equals()/hashCode(): every put() of the "same" SKU adds a new entry. */
    public static final class BadKey {
        final String sku;

        BadKey(String sku) {
            this.sku = sku;
        }
    }

    public static final class PriceRegistry {
        static final Map<BadKey, Long> PRICES = new HashMap<>();

        private PriceRegistry() {
        }
    }

    public static final class AuditEntry {
        final String status;

        AuditEntry(String status) {
            this.status = status;
        }
    }

    public static final class StatusLog {
        static final List<AuditEntry> ENTRIES = new ArrayList<>();

        private StatusLog() {
        }
    }

    /** Eager, never used collections and a boxed field, in every one of {@value #CUSTOMERS} customers. */
    public static final class Customer {
        final List<String> tags = new ArrayList<>(50);
        final Map<String, String> attributes = new HashMap<>(64);
        final Long loyaltyPoints;

        Customer(long i) {
            // HashMap allocates its table on the first put and keeps it after clear()
            attributes.put("created", "true");
            attributes.clear();
            loyaltyPoints = 1_000_000L + i;
        }
    }

    public static final class CustomerRegistry {
        static final List<Customer> ALL = new ArrayList<>();
        static final List<Long> RECENT_IDS = new ArrayList<>();

        private CustomerRegistry() {
        }
    }

    /** Held only by a SoftReference: not a leak, shown as softly reachable. */
    public static final class SoftCache {
        public static final int PAYLOAD_BYTES = 3 * 1024 * 1024 + 17;
        static final SoftReference<byte[]> PAYLOAD = new SoftReference<>(new byte[PAYLOAD_BYTES]);

        private SoftCache() {
        }
    }

    static final int CUSTOMERS = 80_000;

    private ScenarioTarget() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch threadsReady = new CountDownLatch(5);
        classLoaderLeak(threadsReady);
        threadLocals(threadsReady);
        hibernate();
        httpSessions();
        cdi();
        badKeys();
        duplicateStrings();
        customers();
        threadsReady.await();
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(10 * 60_000L);
    }

    private static void classLoaderLeak(CountDownLatch ready) throws IOException {
        byte[] pluginClass = classBytes(DeployedPlugin.class);
        Module old = new Module("deployment.orders.war");
        ModuleClassLoader oldLoader = new ModuleClassLoader(old, ScenarioTarget.class.getClassLoader());
        try {
            // Initialise the class so its static state lives in the old deployment too.
            oldLoader.define(DeployedPlugin.class.getName(), pluginClass).getDeclaredField("STATIC_STATE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        Server.MODULE_LOADER.register(old);
        // Redeploy: the old module leaves the registry, a new one replaces it ...
        Server.MODULE_LOADER.unregister(old);
        Module current = new Module("deployment.orders.war");
        ModuleClassLoader currentLoader = new ModuleClassLoader(current, ScenarioTarget.class.getClassLoader());
        currentLoader.define(DeployedPlugin.class.getName(), pluginClass);
        Server.MODULE_LOADER.register(current);
        Server.ACTIVE_LOADERS.add(currentLoader);
        // ... but a pool thread started by the old deployment still has it as context class loader.
        Thread worker = new Thread(() -> {
            ready.countDown();
            parkForever();
        }, "default task-7");
        worker.setContextClassLoader(oldLoader);
        worker.setDaemon(true);
        worker.start();
    }

    private static void threadLocals(CountDownLatch ready) {
        for (int i = 1; i <= 4; i++) {
            String user = "user-" + i;
            Thread thread = new Thread(() -> {
                RequestContext.CURRENT.set(new UserContext(user));   // never removed
                ready.countDown();
                parkForever();
            }, "default task-" + i);
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void hibernate() {
        Server.batchSession = new SessionImpl();
        for (long id = 0; id < 5_000; id++) {
            Server.batchSession.getPersistenceContext().addEntity("Order#" + id, new OrderEntity(id));
        }
    }

    private static void httpSessions() {
        Server.sessionManager = new InMemorySessionManager("orders.war");
        for (int i = 0; i < 12_000; i++) {
            Server.sessionManager.createSession("S" + i).setAttribute("cart", new CartDto());
        }
    }

    private static void cdi() {
        Server.applicationContext = new ApplicationContextImpl();
        ReportService service = new ReportService();
        for (long i = 0; i < 60_000; i++) {
            service.cache.put(i, "report-" + i);
        }
        CreationalContextImpl creational = new CreationalContextImpl("ReportService bean");
        for (int i = 0; i < 5_000; i++) {
            // Instance<ReportBuilder>.get() without destroy(): Weld remembers every dependent instance
            creational.addDependentInstance(new SerializableContextualInstanceImpl("ReportBuilder bean",
                    new ReportBuilder(), null));
        }
        Server.applicationContext.beanStore().put("reportService",
                new SerializableContextualInstanceImpl("ReportService bean", service, creational));
    }

    private static void badKeys() {
        for (int i = 0; i < 60_000; i++) {
            PriceRegistry.PRICES.put(new BadKey("SKU-" + (i % 30_000)), (long) i);
        }
    }

    private static void duplicateStrings() {
        char[] text = "ORDER_STATUS_PENDING".toCharArray();
        for (int i = 0; i < 40_000; i++) {
            StatusLog.ENTRIES.add(new AuditEntry(new String(text)));
        }
    }

    private static void customers() {
        for (int i = 0; i < CUSTOMERS; i++) {
            CustomerRegistry.ALL.add(new Customer(i));
        }
        for (int i = 0; i < 100_000; i++) {
            CustomerRegistry.RECENT_IDS.add(2_000_000L + i);
        }
        if (SoftCache.PAYLOAD.get() == null) {
            throw new IllegalStateException("soft payload cleared");
        }
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = ScenarioTarget.class.getResourceAsStream(resource)) {
            return in.readAllBytes();
        }
    }

    private static void parkForever() {
        while (true) {
            LockSupport.park();
        }
    }
}
