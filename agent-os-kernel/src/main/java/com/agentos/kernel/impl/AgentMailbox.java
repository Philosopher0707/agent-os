package com.agentos.kernel.impl;

import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public final class AgentMailbox {
    private static final Logger log = LoggerFactory.getLogger(AgentMailbox.class);

    private final BlockingQueue<ACLMessage> queue;
    private final Consumer<ACLMessage> dispatcher;

    public AgentMailbox(int capacity, Consumer<ACLMessage> dispatcher) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.dispatcher = dispatcher;
    }

    /** Expose the underlying queue for transport integration */
    public BlockingQueue<ACLMessage> queue() { return queue; }

    public boolean deliver(ACLMessage msg) {
        boolean accepted = queue.offer(msg);
        if (!accepted) {
            ACLMessage oldest = queue.poll();
            if (oldest != null) {
                log.warn("Mailbox full ({}), dropped oldest msg conv={}", queue.size() + 1, oldest.conversationId());
            }
            accepted = queue.offer(msg);
        }
        return accepted;
    }

    public void drain(Consumer<ACLMessage> overflowHandler) {
        ACLMessage msg;
        while ((msg = queue.poll()) != null) {
            try {
                dispatcher.accept(msg);
            } catch (Exception e) {
                log.warn("Dispatch error for msg {}: {}", msg.conversationId(), e.getMessage());
                overflowHandler.accept(msg);
            }
        }
    }

    public int size() { return queue.size(); }
    public void clear() { queue.clear(); }
}
