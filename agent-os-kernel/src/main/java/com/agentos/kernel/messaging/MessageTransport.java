package com.agentos.kernel.messaging;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface MessageTransport extends AutoCloseable {
    String scheme();

    CompletableFuture<Void> send(ACLMessage msg);

    void receive(Consumer<ACLMessage> handler);

    void start();

    @Override
    void close();

    /** Register which container an agent lives in — for targeted message routing. */
    default void registerAgent(String agentName, String containerId) {}

    /** Unregister an agent from routing. */
    default void unregisterAgent(String agentName) {}
}
