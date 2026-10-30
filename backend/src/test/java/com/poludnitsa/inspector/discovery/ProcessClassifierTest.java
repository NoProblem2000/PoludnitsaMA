package com.poludnitsa.inspector.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.poludnitsa.inspector.api.dto.ProcessType;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;

class ProcessClassifierTest {

    private final ProcessClassifier classifier = new ProcessClassifier();

    @Test
    void wildFlyStandaloneFromSystemProperties() {
        Properties props = props("jboss.home.dir", "/opt/wildfly-30.0.1.Final", "jboss.server.name", "orders-node1",
                "hazelcast.client.config", "/etc/hz-client.xml");
        ProcessFacts facts = new ProcessFacts(props,
                "/opt/wildfly/jboss-modules.jar -mp /opt/wildfly/modules org.jboss.as.standalone", List.of(), null);

        assertThat(classifier.classify(facts))
                .isEqualTo(new ProcessClassifier.Classification(ProcessType.WILDFLY,
                        "WildFly orders-node1 (wildfly-30.0.1.Final)"));
    }

    @Test
    void wildFlyDomainServerFromProcessNameArgument() {
        ProcessFacts facts = new ProcessFacts(null, "/opt/wildfly/jboss-modules.jar -mp /opt/wildfly/modules"
                + " org.jboss.as.server", List.of("-D[Server:server-one]", "-Xmx2g"), null);

        assertThat(classifier.classify(facts).displayName()).isEqualTo("WildFly Server:server-one");
    }

    @Test
    void legacyJBossMainWithoutAttach() {
        ProcessFacts facts = new ProcessFacts(null, "org.jboss.Main -c default", List.of(), null);

        assertThat(classifier.classify(facts).type()).isEqualTo(ProcessType.WILDFLY);
    }

    @Test
    void hazelcastMemberFromMainClass() {
        ProcessFacts facts = new ProcessFacts(props("hazelcast.config", "/opt/hazelcast/config/hazelcast.xml"),
                "com.hazelcast.commandline.HazelcastServerCommandLine start", List.of(), null);

        assertThat(classifier.classify(facts))
                .isEqualTo(new ProcessClassifier.Classification(ProcessType.HAZELCAST, "Hazelcast member (hazelcast.xml)"));
    }

    @Test
    void hazelcastMemberFromMemberProperties() {
        ProcessFacts facts = new ProcessFacts(props("hazelcast.config", "/etc/hz.yaml",
                "java.class.path", "/opt/hz/lib/hazelcast-5.3.6.jar:/opt/hz/app.jar"),
                "com.acme.cache.Main", List.of(), null);

        assertThat(classifier.classify(facts).type()).isEqualTo(ProcessType.HAZELCAST);
    }

    @Test
    void springBootFatJarWithHazelcastClientIsSpringBoot() {
        Manifest manifest = manifest("org.springframework.boot.loader.launch.JarLauncher", "com.acme.OrdersApplication");
        ProcessFacts facts = new ProcessFacts(props("hazelcast.client.config", "/etc/hz-client.yaml"),
                "/srv/orders/orders-service.jar --server.port=8081", List.of(), manifest);

        assertThat(classifier.classify(facts))
                .isEqualTo(new ProcessClassifier.Classification(ProcessType.SPRING_BOOT,
                        "Spring Boot orders-service.jar"));
    }

    @Test
    void springBootFromIdeClassPath() {
        ProcessFacts facts = new ProcessFacts(props("java.class.path",
                "/home/dev/.m2/org/springframework/boot/spring-boot-3.5.6.jar:/home/dev/app/target/classes"),
                "com.acme.OrdersApplication", List.of(), null);

        assertThat(classifier.classify(facts))
                .isEqualTo(new ProcessClassifier.Classification(ProcessType.SPRING_BOOT,
                        "Spring Boot OrdersApplication"));
    }

    @Test
    void plainJvm() {
        assertThat(classifier.classify(new ProcessFacts(null, "com.acme.batch.Importer --full", List.of(), null)))
                .isEqualTo(new ProcessClassifier.Classification(ProcessType.STANDARD_JVM, "Importer"));
        assertThat(classifier.classify(new ProcessFacts(null, "/opt/tools/report.jar", List.of(), null)))
                .isEqualTo(new ProcessClassifier.Classification(ProcessType.STANDARD_JVM, "report.jar"));
        assertThat(classifier.classify(new ProcessFacts(null, "", List.of(), null)).displayName()).isEqualTo("java");
    }

    private static Properties props(String... keyValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return properties;
    }

    private static Manifest manifest(String mainClass, String startClass) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        attributes.putValue("Start-Class", startClass);
        return manifest;
    }
}
