package com.poludnitsa.inspector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InspectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(InspectorApplication.class, args);
    }
}
