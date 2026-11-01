package com.poludnitsa.inspector.profiler;

import com.poludnitsa.inspector.api.dto.PackageCategory;
import com.poludnitsa.inspector.api.dto.Subsystem;
import java.util.List;

/**
 * Assigns the weight of a stack to a subsystem.
 *
 * <p>Samples are judged from the top of the stack (leaf) down: the first JDBC / driver / pool frame makes it
 * <b>JDBC</b>, the first Hibernate frame <b>HIBERNATE</b>, the first application frame <b>BUSINESS</b> (your code, or
 * JDK code it calls), and only time spent in Undertow / servlet / RESTEasy frames without any of those above them is
 * <b>HTTP</b> (request plumbing itself). A request stack {@code Undertow → OrderResource → Hibernate → PgStatement →
 * socketRead} is therefore JDBC time, not HTTP time.
 *
 * <p>Duration events (park, monitor, sleep, socket / file I/O) are waiting: inside JDBC or Hibernate they count there
 * (a query, a pool wait), otherwise as <b>IO</b> or <b>LOCKS</b>.
 */
public final class SubsystemClassifier {

    static final List<String> JDBC = List.of("java.sql.", "javax.sql.", "org.postgresql.", "oracle.jdbc.",
            "com.mysql.", "org.mariadb.", "org.h2.", "com.microsoft.sqlserver.", "com.ibm.db2.", "io.agroal.",
            "com.zaxxer.hikari.", "org.jboss.jca.adapters.jdbc.");
    static final List<String> HIBERNATE = List.of("org.hibernate.");
    static final List<String> HTTP = List.of("io.undertow.", "jakarta.servlet.", "javax.servlet.",
            "org.jboss.resteasy.", "org.wildfly.extension.undertow.", "jakarta.ws.rs.");

    private SubsystemClassifier() {
    }

    /** JDBC API, drivers and connection pools. */
    public static boolean isJdbc(String className) {
        return startsWith(className, JDBC);
    }

    public static Subsystem classifySample(ProfileData data, int stack) {
        int[] frames = data.stack(stack);
        boolean framework = false;
        for (int frameId : frames) {
            ProfileData.Frame frame = data.frame(frameId);
            String name = frame.className();
            if (startsWith(name, JDBC)) {
                return Subsystem.JDBC;
            }
            if (startsWith(name, HIBERNATE)) {
                return Subsystem.HIBERNATE;
            }
            if (frame.category() == PackageCategory.APPLICATION) {
                return Subsystem.BUSINESS;
            }
            if (startsWith(name, HTTP)) {
                return Subsystem.HTTP;
            }
            framework |= frame.category() != PackageCategory.JDK && frame.category() != PackageCategory.PRIMITIVE_OR_ARRAY;
        }
        return framework ? Subsystem.FRAMEWORK : Subsystem.JDK;
    }

    public static Subsystem classifyEvent(ProfileData data, ProfileData.EventKind kind, int stack) {
        if (stack >= 0) {
            for (int frameId : data.stack(stack)) {
                String name = data.frame(frameId).className();
                if (startsWith(name, JDBC)) {
                    return Subsystem.JDBC;
                }
                if (startsWith(name, HIBERNATE)) {
                    return Subsystem.HIBERNATE;
                }
            }
        }
        return kind.isIo() ? Subsystem.IO : Subsystem.LOCKS;
    }

    public static String label(Subsystem subsystem) {
        return switch (subsystem) {
            case BUSINESS -> "Бизнес-логика";
            case JDBC -> "JDBC / SQL";
            case HIBERNATE -> "Hibernate ORM";
            case HTTP -> "HTTP (Undertow / Servlet)";
            case IO -> "Socket / File I/O";
            case LOCKS -> "Блокировки и ожидание";
            case GC -> "GC-паузы";
            case FRAMEWORK -> "Сервер и библиотеки";
            case JDK -> "JDK";
        };
    }

    private static boolean startsWith(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
