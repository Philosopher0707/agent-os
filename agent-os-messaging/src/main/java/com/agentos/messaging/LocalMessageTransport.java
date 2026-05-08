package com.agentos.messaging;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class LocalMessageTransport implements MessageTransport {
    private final Map<AgentId, BlockingQueue<ACLMessage>> mailboxes = new ConcurrentHashMap<>();
    private Consumer<ACLMessage> inboundHandler;

    public void registerMailbox(AgentId id, BlockingQueue<ACLMessage> queue) {
        mailboxes.put(id, queue);
    }

    public void unregisterMailbox(AgentId id) {
        mailboxes.remove(id);
    }

    @Override public String scheme() { return "local"; }

    @Override
    public CompletableFuture<Void> send(ACLMessage msg) {
        for (AgentId receiver : msg.receivers()) {
            BlockingQueue<ACLMessage> queue = mailboxes.get(receiver);
            if (queue != null) {
                if (!queue.offer(msg)) {
                    // Mailbox full — drop oldest and retry
                    ACLMessage oldest = queue.poll();
                    queue.offer(msg);
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override public void receive(Consumer<ACLMessage> handler) { this.inboundHandler = handler; }

    @Override public void start() {}

    @Override public void close() { mailboxes.clear(); }

    /** Drain a specific agent's mailbox, dispatching each message to the handler */
    public void drainMailbox(AgentId id, Consumer<ACLMessage> dispatcher,
                             Consumer<ACLMessage> overflowHandler) {
        BlockingQueue<ACLMessage> queue = mailboxes.get(id);
        if (queue == null) return;
        ACLMessage msg;
        while ((msg = queue.poll()) != null) {
            try {
                dispatcher.accept(msg);
            } catch (Exception e) {
                overflowHandler.accept(msg);
            }
        }
    }
}
