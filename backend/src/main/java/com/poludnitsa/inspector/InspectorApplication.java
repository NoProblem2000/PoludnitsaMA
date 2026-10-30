package com.poludnitsa.inspector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class InspectorApplication {

    public static void main(String[] args) {
        // Socket connect timeout for RMI. Response timeouts are per call (JmxConnectionManager): a global
        // sun.rmi.transport.tcp.responseTimeout would also cut off multi-minute heap dumps.
        setIfAbsent("sun.rmi.transport.proxy.connectTimeout", "5000");
        SpringApplication.run(InspectorApplication.class, args);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
