package com.poludnitsa.inspector.jmx;

import com.poludnitsa.inspector.api.dto.GcGeneration;
import com.poludnitsa.inspector.api.dto.MemoryPoolKind;
import com.poludnitsa.inspector.api.dto.MemoryPoolType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

/**
 * Static facts about a connected target, read once per connection: identity, pools, collectors and which optional
 * MBeans exist. Pools and collectors are fixed for the lifetime of a JVM.
 */
public record TargetMetadata(
        long pid,
        long startTimeEpochMs,
        String vmVersion,
        List<String> inputArguments,
        List<PoolInfo> pools,
        List<CollectorInfo> collectors,
        boolean diagnosticCommandAvailable,
        boolean hotSpotDiagnosticAvailable,
        boolean flightRecorderAvailable,
        boolean totalAllocatedBytesAvailable
) {

    public record PoolInfo(ObjectName objectName, String name, MemoryPoolType type, MemoryPoolKind kind) {
    }

    public record CollectorInfo(ObjectName objectName, String name, GcGeneration generation, boolean stopTheWorld) {
    }

    public TargetMetadata {
        inputArguments = List.copyOf(inputArguments);
        pools = List.copyOf(pools);
        collectors = List.copyOf(collectors);
    }

    /** The pool whose post-GC usage reflects the old generation; single-pool collectors use their only heap pool. */
    public Optional<PoolInfo> oldGenerationPool() {
        Optional<PoolInfo> old = pools.stream().filter(p -> p.kind() == MemoryPoolKind.OLD).findFirst();
        if (old.isPresent()) {
            return old;
        }
        List<PoolInfo> heapPools = pools.stream().filter(p -> p.type() == MemoryPoolType.HEAP).toList();
        return heapPools.size() == 1 ? Optional.of(heapPools.get(0)) : Optional.empty();
    }

    public static TargetMetadata load(MBeanServerConnection connection) throws IOException, JMException {
        AttributeList runtime = connection.getAttributes(MBeanNames.RUNTIME,
                new String[] {"Pid", "StartTime", "VmVersion", "InputArguments"});
        Object pid = JmxAttributes.get(runtime, "Pid");
        if (!(pid instanceof Long)) {
            throw new IOException("Target does not expose RuntimeMXBean.Pid; Java 10+ is required");
        }
        Object args = JmxAttributes.get(runtime, "InputArguments");
        List<String> inputArguments = args instanceof String[] array ? Arrays.asList(array) : List.of();

        List<PoolInfo> pools = new ArrayList<>();
        for (ObjectName name : connection.queryNames(MBeanNames.MEMORY_POOLS, null)) {
            AttributeList attributes = connection.getAttributes(name, new String[] {"Name", "Type"});
            String poolName = String.valueOf(JmxAttributes.get(attributes, "Name"));
            MemoryPoolType type = "HEAP".equals(String.valueOf(JmxAttributes.get(attributes, "Type")))
                    ? MemoryPoolType.HEAP
                    : MemoryPoolType.NON_HEAP;
            pools.add(new PoolInfo(name, poolName, type, MemoryPoolClassifier.kind(poolName, type)));
        }
        pools.sort(Comparator.comparing(PoolInfo::type).thenComparing(PoolInfo::kind).thenComparing(PoolInfo::name));

        List<CollectorInfo> collectors = new ArrayList<>();
        for (ObjectName name : connection.queryNames(MBeanNames.GARBAGE_COLLECTORS, null)) {
            String collectorName = String.valueOf(connection.getAttribute(name, "Name"));
            collectors.add(new CollectorInfo(name, collectorName, GcCollectorClassifier.generation(collectorName),
                    GcCollectorClassifier.stopTheWorld(collectorName)));
        }
        collectors.sort(Comparator.comparing(CollectorInfo::name));

        return new TargetMetadata(
                (Long) pid,
                JmxAttributes.longValue(JmxAttributes.get(runtime, "StartTime"), 0),
                String.valueOf(JmxAttributes.get(runtime, "VmVersion")),
                inputArguments,
                pools,
                collectors,
                connection.isRegistered(MBeanNames.DIAGNOSTIC_COMMAND),
                connection.isRegistered(MBeanNames.HOTSPOT_DIAGNOSTIC),
                connection.isRegistered(MBeanNames.FLIGHT_RECORDER),
                hasAttribute(connection, MBeanNames.THREADING, "TotalThreadAllocatedBytes"));
    }

    private static boolean hasAttribute(MBeanServerConnection connection, ObjectName name, String attribute)
            throws IOException, JMException {
        for (MBeanAttributeInfo info : connection.getMBeanInfo(name).getAttributes()) {
            if (info.getName().equals(attribute)) {
                return true;
            }
        }
        return false;
    }
}
