package com.poludnitsa.inspector.jmx;

import java.io.IOException;
import javax.management.JMException;
import javax.management.MBeanServerConnection;

/** Work executed against a target's MBean server by {@link JmxConnectionManager#call}. */
@FunctionalInterface
public interface JmxCall<T> {

    T call(MBeanServerConnection connection, TargetMetadata metadata) throws IOException, JMException;
}
