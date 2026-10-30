package com.poludnitsa.inspector.discovery;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Attach API access for discovery, isolated so the rest of the code works on runtimes without {@code jdk.attach}. */
final class LocalJvmProbe {

    /** A JVM visible through the target users' {@code hsperfdata} (same OS user as the inspector). */
    record LocalJvm(long pid, String command) {
    }

    /** Properties read through the attach channel without loading any agent. */
    record AttachedProperties(Properties systemProperties, Properties agentProperties) {
    }

    private LocalJvmProbe() {
    }

    static List<LocalJvm> list() {
        List<LocalJvm> result = new ArrayList<>();
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            try {
                result.add(new LocalJvm(Long.parseLong(descriptor.id()), descriptor.displayName()));
            } catch (NumberFormatException ignored) {
                // not a PID-based descriptor
            }
        }
        return result;
    }

    static AttachedProperties read(long pid) throws IOException, AttachNotSupportedException {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            return new AttachedProperties(vm.getSystemProperties(), vm.getAgentProperties());
        } finally {
            vm.detach();
        }
    }
}
