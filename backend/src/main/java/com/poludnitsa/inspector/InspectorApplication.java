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
        // A hung target must not block a JMX call (and the sampler thread) forever.
        setIfAbsent("sun.rmi.transport.tcp.responseTimeout", "15000");
        setIfAbsent("sun.rmi.transport.proxy.connectTimeout", "5000");
        SpringApplication.run(InspectorApplication.class, args);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}
