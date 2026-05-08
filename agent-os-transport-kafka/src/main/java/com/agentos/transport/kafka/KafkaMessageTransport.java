package com.agentos.transport.kafka;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.messaging.MessageTransport;
import com.agentos.messaging.MessageSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Kafka-based MessageTransport for multi-cluster agent communication.
 *
 * Topics:
 *   agentos.messages.{containerId}  — per-container inbox (each container consumes its own)
 *   agentos.broadcast               — for broadcast messages (all containers consume)
 *
 * Routing:
 *   - If a receiver's container is known, message goes to agentos.messages.{targetContainer}
 *   - If unknown, message goes to agentos.broadcast
 *   - Each container consumes its own topic + broadcast
 */
public final class KafkaMessageTransport implements MessageTransport {
    private static final Logger log = LoggerFactory.getLogger(KafkaMessageTransport.class);

    private static final String TOPIC_PREFIX = "agentos.messages.";
    private static final String BROADCAST_TOPIC = "agentos.broadcast";

    private final String containerId;
    private final String bootstrapServers;
    private final Properties producerProps;
    private final Properties consumerProps;
    private final String consumerGroup;

    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private Consumer<ACLMessage> inboundHandler;
    private volatile boolean running;
    private final ExecutorService consumerExecutor;
    private final Map<String, String> agentToContainer = new ConcurrentHashMap<>();

    /**
     * @param containerId      unique ID for this kernel container
     * @param bootstrapServers Kafka bootstrap servers (e.g., "localhost:9092")
     * @param overrides        optional producer/consumer config overrides
     */
    public KafkaMessageTransport(String containerId, String bootstrapServers,
                                  Map<String, String> overrides) {
        this.containerId = containerId;
        this.bootstrapServers = bootstrapServers;
        this.consumerGroup = "agentos-" + containerId;

        this.producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        producerProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");

        this.consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        if (overrides != null) {
            overrides.forEach((k, v) -> {
                if (k.startsWith("producer.")) {
                    producerProps.put(k.substring("producer.".length()), v);
                } else if (k.startsWith("consumer.")) {
                    consumerProps.put(k.substring("consumer.".length()), v);
                }
            });
        }

        this.consumerExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "kafka-consumer-" + containerId); t.setDaemon(true); return t; }
        );
    }

    public KafkaMessageTransport(String containerId, String bootstrapServers) {
        this(containerId, bootstrapServers, Map.of());
    }

    @Override
    public String scheme() {
        return "kafka";
    }

    @Override
    public void start() {
        producer = new KafkaProducer<>(producerProps);
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(List.of(inboxTopic(), BROADCAST_TOPIC));
        running = true;

        consumerExecutor.submit(this::consumeLoop);
        log.info("Kafka transport started: container={}, servers={}, topics=[{}, {}]",
            containerId, bootstrapServers, inboxTopic(), BROADCAST_TOPIC);
    }

    private String inboxTopic() {
        return TOPIC_PREFIX + containerId;
    }

    private void consumeLoop() {
        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        MessageSerializer.fromJson(record.value()).ifPresent(msg -> {
                            if (inboundHandler != null) {
                                inboundHandler.accept(msg);
                            }
                        });
                    } catch (Exception e) {
                        log.warn("Failed to deserialize Kafka message from topic={} offset={}: {}",
                            record.topic(), record.offset(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                log.error("Kafka consumer loop error: {}", e.getMessage(), e);
            }
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public CompletableFuture<Void> send(ACLMessage msg) {
        String json = MessageSerializer.toJson(msg);
        String key = msg.conversationId();

        // Determine target topics — use agent-to-container routing when available
        Set<String> targetTopics = new LinkedHashSet<>();
        for (AgentId receiver : msg.receivers()) {
            String name = receiver.name();
            // Check local routing table first
            String containerFromRouter = agentToContainer.get(name);
            if (containerFromRouter != null) {
                targetTopics.add(TOPIC_PREFIX + containerFromRouter);
                continue;
            }
            // Extract container from agent name: "agent@container" -> "container"
            int atIdx = name.indexOf('@');
            if (atIdx > 0) {
                targetTopics.add(TOPIC_PREFIX + name.substring(atIdx + 1));
            } else {
                // No container hint — use broadcast
                targetTopics.add(BROADCAST_TOPIC);
            }
        }

        if (targetTopics.isEmpty()) {
            targetTopics.add(BROADCAST_TOPIC);
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String topic : targetTopics) {
            CompletableFuture<Void> f = new CompletableFuture<>();
            futures.add(f);
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.warn("Kafka send to topic={} failed: {}", topic, exception.getMessage());
                    f.completeExceptionally(exception);
                } else {
                    f.complete(null);
                }
            });
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public void receive(Consumer<ACLMessage> handler) {
        this.inboundHandler = handler;
    }

    @Override
    public void close() {
        running = false;
        consumerExecutor.shutdown();
        try { consumerExecutor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (producer != null) { producer.close(Duration.ofSeconds(5)); }
        log.info("Kafka transport closed for container={}", containerId);
    }

    @Override
    public void registerAgent(String agentName, String containerId) {
        agentToContainer.put(agentName, containerId);
    }

    @Override
    public void unregisterAgent(String agentName) {
        agentToContainer.remove(agentName);
    }
}
