package com.poludnitsa.inspector.jmx;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/** Object names of the platform and HotSpot MBeans read from target JVMs. */
public final class MBeanNames {

    public static final ObjectName MEMORY = of("java.lang:type=Memory");
    public static final ObjectName RUNTIME = of("java.lang:type=Runtime");
    public static final ObjectName THREADING = of("java.lang:type=Threading");
    public static final ObjectName MEMORY_POOLS = of("java.lang:type=MemoryPool,name=*");
    public static final ObjectName GARBAGE_COLLECTORS = of("java.lang:type=GarbageCollector,name=*");
    public static final ObjectName DIAGNOSTIC_COMMAND = of("com.sun.management:type=DiagnosticCommand");
    public static final ObjectName HOTSPOT_DIAGNOSTIC = of("com.sun.management:type=HotSpotDiagnostic");
    public static final ObjectName FLIGHT_RECORDER = of("jdk.management.jfr:type=FlightRecorder");

    private MBeanNames() {
    }

    public static ObjectName of(String name) {
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException(name, e);
        }
    }
}
