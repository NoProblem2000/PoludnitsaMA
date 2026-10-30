package com.poludnitsa.inspector.inspection.rules.hazelcast;

import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.serialization.impl.DefaultSerializationServiceBuilder;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.DataSerializable;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import java.io.IOException;
import java.io.Serializable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The decoder against payloads produced by Hazelcast's own serialization service. */
class HazelcastPayloadDecoderTest {

    static final class Order implements Serializable {
        private static final long serialVersionUID = 1L;
        final long id = 42;
    }

    public static final class Customer implements IdentifiedDataSerializable {
        @Override
        public int getFactoryId() {
            return 1000;
        }

        @Override
        public int getClassId() {
            return 7;
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeInt(1);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            in.readInt();
        }
    }

    public static final class Address implements DataSerializable {
        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeString("Main St");
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            in.readString();
        }
    }

    private final InternalSerializationService serialization = new DefaultSerializationServiceBuilder()
            .addDataSerializableFactory(1000, id -> new Customer())
            .build();

    @AfterEach
    void tearDown() {
        serialization.dispose();
    }

    @Test
    void javaSerializableWithClassName() {
        assertThat(decode(new Order())).isEqualTo("Java Serializable " + Order.class.getName());
    }

    @Test
    void identifiedDataSerializable() {
        assertThat(decode(new Customer())).isEqualTo("IdentifiedDataSerializable factoryId=1000 classId=7");
    }

    @Test
    void dataSerializableWithClassName() {
        assertThat(decode(new Address())).isEqualTo("DataSerializable " + Address.class.getName());
    }

    @Test
    void builtInTypes() {
        assertThat(decode("hello")).isEqualTo("String");
        assertThat(decode(42L)).isEqualTo("Long");
        assertThat(decode(7)).isEqualTo("Integer");
        assertThat(decode(new byte[] {1, 2, 3})).isEqualTo("byte[]");
    }

    @Test
    void damagedPayload() {
        assertThat(HazelcastPayloadDecoder.describe(new byte[3])).contains("повреждённый");
        assertThat(HazelcastPayloadDecoder.describe(new byte[] {0, 0, 0, 0, 0, 0, 0, 99}))
                .isEqualTo("пользовательский StreamSerializer typeId=99");
    }

    private String decode(Object value) {
        return HazelcastPayloadDecoder.describe(serialization.toData(value).toByteArray());
    }
}
