package com.poludnitsa.inspector.jmx;

import java.io.IOException;
import java.lang.management.MemoryUsage;
import java.util.Properties;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanServerConnection;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/** Helpers for raw attribute access; bulk {@code getAttributes} keeps each sample to a few round trips. */
public final class JmxAttributes {

    private JmxAttributes() {
    }

    /** {@code getAttributes} silently omits attributes it failed to read, so lookups are by name. */
    public static Object get(AttributeList attributes, String name) {
        for (Attribute attribute : attributes.asList()) {
            if (attribute.getName().equals(name)) {
                return attribute.getValue();
            }
        }
        return null;
    }

    public static MemoryUsage memoryUsage(Object value) {
        return value instanceof CompositeData data ? MemoryUsage.from(data) : null;
    }

    public static long longValue(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    public static long heapUsed(MBeanServerConnection connection) throws IOException, JMException {
        MemoryUsage usage = memoryUsage(connection.getAttribute(MBeanNames.MEMORY, "HeapMemoryUsage"));
        return usage == null ? -1 : usage.getUsed();
    }

    /** {@code RuntimeMXBean#getSystemProperties()} of the target. */
    public static Properties systemProperties(MBeanServerConnection connection) throws IOException, JMException {
        Properties properties = new Properties();
        Object value = connection.getAttribute(MBeanNames.RUNTIME, "SystemProperties");
        if (value instanceof TabularData table) {
            for (Object row : table.values()) {
                CompositeData entry = (CompositeData) row;
                Object key = entry.get("key");
                Object propertyValue = entry.get("value");
                if (key != null && propertyValue != null) {
                    properties.setProperty(key.toString(), propertyValue.toString());
                }
            }
        }
        return properties;
    }
}
