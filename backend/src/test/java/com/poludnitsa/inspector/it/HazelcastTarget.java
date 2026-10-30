package com.poludnitsa.inspector.it;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.cluster.Address;
import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.InMemoryFormat;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import java.io.IOException;
import java.io.Serializable;

/**
 * Child JVM with a real Hazelcast member and a client in the same process: an unbounded Near Cache filled with Java
 * Serializable and IdentifiedDataSerializable values, and 150 entry listeners registered by one heavy service.
 */
public final class HazelcastTarget {

    static final int FACTORY_ID = 1000;
    static final int CUSTOMER_CLASS_ID = 7;

    public static final class OrderPayload implements Serializable {
        private static final long serialVersionUID = 1L;
        final int id;
        final byte[] body = new byte[200];

        OrderPayload(int id) {
            this.id = id;
        }
    }

    public static final class Customer implements IdentifiedDataSerializable {
        int id;
        String name;

        public Customer() {
        }

        Customer(int id) {
            this.id = id;
            this.name = "customer-" + id;
        }

        @Override
        public int getFactoryId() {
            return FACTORY_ID;
        }

        @Override
        public int getClassId() {
            return CUSTOMER_CLASS_ID;
        }

        @Override
        public void writeData(ObjectDataOutput out) throws IOException {
            out.writeInt(id);
            out.writeString(name);
        }

        @Override
        public void readData(ObjectDataInput in) throws IOException {
            id = in.readInt();
            name = in.readString();
        }
    }

    /** Registers a listener per call and never removes it; the anonymous listeners keep {@code this} alive. */
    public static final class OrderService {
        final byte[] state = new byte[3 * 1024 * 1024];
        volatile long handled;

        void subscribe(IMap<Integer, OrderPayload> orders) {
            orders.addEntryListener(new EntryAddedListener<Integer, OrderPayload>() {
                @Override
                public void entryAdded(EntryEvent<Integer, OrderPayload> event) {
                    handled++;
                }
            }, false);
        }
    }

    private HazelcastTarget() {
    }

    public static void main(String[] args) throws Exception {
        String cluster = "inspector-test-" + ProcessHandle.current().pid();
        Config config = new Config().setClusterName(cluster);
        config.setProperty("hazelcast.phone.home.enabled", "false");
        config.getJetConfig().setEnabled(false);
        config.getNetworkConfig().setPort(5901).setPortAutoIncrement(true).setPortCount(100);
        config.getNetworkConfig().getInterfaces().setEnabled(true).addInterface("127.0.0.1");
        JoinConfig join = config.getNetworkConfig().getJoin();
        join.getMulticastConfig().setEnabled(false);
        join.getAutoDetectionConfig().setEnabled(false);
        join.getTcpIpConfig().setEnabled(true).addMember("127.0.0.1");
        config.getSerializationConfig().addDataSerializableFactory(FACTORY_ID,
                id -> id == CUSTOMER_CLASS_ID ? new Customer() : null);
        HazelcastInstance member = Hazelcast.newHazelcastInstance(config);
        Address address = member.getCluster().getLocalMember().getAddress();

        ClientConfig clientConfig = new ClientConfig().setClusterName(cluster);
        clientConfig.getNetworkConfig().addAddress(address.getHost() + ":" + address.getPort());
        clientConfig.getSerializationConfig().addDataSerializableFactory(FACTORY_ID,
                id -> id == CUSTOMER_CLASS_ID ? new Customer() : null);
        for (String name : new String[] {"orders", "customers"}) {
            NearCacheConfig nearCache = new NearCacheConfig(name).setInMemoryFormat(InMemoryFormat.BINARY);
            nearCache.getEvictionConfig().setEvictionPolicy(EvictionPolicy.NONE).setSize(Integer.MAX_VALUE);
            clientConfig.addNearCacheConfig(nearCache);
        }
        HazelcastInstance client = HazelcastClient.newHazelcastClient(clientConfig);

        IMap<Integer, OrderPayload> orders = client.getMap("orders");
        IMap<Integer, Customer> customers = client.getMap("customers");
        for (int i = 0; i < 20_000; i++) {
            orders.put(i, new OrderPayload(i));
        }
        for (int i = 0; i < 10_000; i++) {
            customers.put(i, new Customer(i));
        }
        for (int i = 0; i < 20_000; i++) {
            orders.get(i);
        }
        for (int i = 0; i < 10_000; i++) {
            customers.get(i);
        }
        OrderService service = new OrderService();
        for (int i = 0; i < 150; i++) {
            service.subscribe(orders);
        }
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(10 * 60_000L);
    }
}
