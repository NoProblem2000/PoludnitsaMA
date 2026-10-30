package com.poludnitsa.inspector.jmx;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import java.io.IOException;

/** Attach API access, isolated so the rest of the code works on runtimes without {@code jdk.attach}. */
final class LocalAttach {

    static final String LOCAL_CONNECTOR_ADDRESS = "com.sun.management.jmxremote.localConnectorAddress";

    private LocalAttach() {
    }

    /**
     * Returns the local JMX connector address of the target, starting its local management agent if needed.
     * {@code startLocalManagementAgent()} is idempotent and only binds to the loopback interface.
     */
    static String localConnectorAddress(long pid) throws IOException, AttachNotSupportedException {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            String address = vm.getAgentProperties().getProperty(LOCAL_CONNECTOR_ADDRESS);
            return address != null ? address : vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
    }
}
