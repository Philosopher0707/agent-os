package com.agentos.transport.kafka;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@EnabledIf("dockerAvailable")
@Timeout(120)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaMessageTransportTest {

    static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "ps").start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("apache/kafka-native:3.8.1"));

    private String bootstrapServers;

    @BeforeAll
    void setUp() {
        bootstrapServers = kafka.getBootstrapServers();
    }

    @Test
    void shouldReturnKafkaScheme() {
        var transport = new KafkaMessageTransport("test", bootstrapServers);
        assertThat(transport.scheme()).isEqualTo("kafka");
        transport.close();
    }

    @Test
    void shouldSendAndConsumeMessage() throws Exception {
        String containerId = "test-c" + UUID.randomUUID().toString().substring(0, 4);
        var transport = new KafkaMessageTransport(containerId, bootstrapServers);

        CountDownLatch received = new CountDownLatch(1);
        transport.receive(msg -> received.countDown());
        transport.start();

        // Wait for consumer group to stabilize
        Thread.sleep(500);

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("alice"))
            .receiver(AgentId.of("bob"))
            .content("hello-kafka")
            .build();

        transport.send(msg).get(10, TimeUnit.SECONDS);
        assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();

        transport.close();
    }

    @Test
    void shouldRouteToAgentContainer() throws Exception {
        String containerA = "cA" + UUID.randomUUID().toString().substring(0, 4);
        String containerB = "cB" + UUID.randomUUID().toString().substring(0, 4);

        // Sender transport
        var senderTransport = new KafkaMessageTransport(containerA, bootstrapServers);
        senderTransport.registerAgent("charlie", containerB);
        senderTransport.start();

        // Receiver transport (the target container)
        var receiverTransport = new KafkaMessageTransport(containerB, bootstrapServers);
        CountDownLatch received = new CountDownLatch(1);
        receiverTransport.receive(msg -> {
            if (msg.content().contains("container-routed")) received.countDown();
        });
        receiverTransport.start();

        Thread.sleep(800); // let consumers join groups

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("alice"))
            .receiver(AgentId.of("charlie"))
            .content("container-routed")
            .build();

        senderTransport.send(msg).get(10, TimeUnit.SECONDS);
        assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();

        senderTransport.close();
        receiverTransport.close();
    }

    @Test
    void shouldUseBroadcastForUnknownAgent() throws Exception {
        String containerId = "test-bc" + UUID.randomUUID().toString().substring(0, 4);
        var transport = new KafkaMessageTransport(containerId, bootstrapServers);

        CountDownLatch received = new CountDownLatch(1);
        transport.receive(msg -> {
            if (msg.content().contains("broadcast-test")) received.countDown();
        });
        transport.start();
        Thread.sleep(500);

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("alice"))
            .receiver(AgentId.of("unknown-agent"))
            .content("broadcast-test")
            .build();

        transport.send(msg).get(10, TimeUnit.SECONDS);
        assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();

        transport.close();
    }

    @Test
    void shouldCloseWithoutErrors() {
        var transport = new KafkaMessageTransport("test-close", bootstrapServers);
        transport.start();
        assertThatNoException().isThrownBy(transport::close);
    }
}
