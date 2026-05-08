package com.agentos.kernel.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates FIPA Subscribe protocol conversations.
 * SUBSCRIBE → AGREE/REFUSE → INFORM (repeated) → CANCEL.
 */
public final class SubscribeProtocol implements MessagingProtocol {
    private static final Logger log = LoggerFactory.getLogger(SubscribeProtocol.class);

    private final Map<String, State> conversations = new ConcurrentHashMap<>();

    private enum State { SUBSCRIBED, AGREED, REFUSED, CANCELLED }

    @Override
    public Optional<Violation> validate(String conversationId, ACLMessage msg) {
        if (conversationId == null) return Optional.empty();
        if (!"fipa-subscribe".equals(msg.protocol())) return Optional.empty();

        var perf = msg.performative();
        var current = conversations.get(conversationId);

        switch (perf) {
            case SUBSCRIBE -> {
                if (current != null) return violation(conversationId, "duplicate SUBSCRIBE");
                conversations.put(conversationId, State.SUBSCRIBED);
            }
            case REFUSE -> {
                if (current != null) return violation(conversationId, "REFUSE after subscribe started");
                conversations.put(conversationId, State.REFUSED);
            }
            case AGREE -> {
                if (current == State.SUBSCRIBED) {
                    conversations.put(conversationId, State.AGREED);
                } else if (current != State.AGREED) {
                    return violation(conversationId, "AGREE without prior SUBSCRIBE");
                }
            }
            case INFORM -> {
                if (current != State.AGREED) {
                    return violation(conversationId, "INFORM without AGREE after SUBSCRIBE");
                }
            }
            case CANCEL -> {
                if (current == State.SUBSCRIBED) conversations.put(conversationId, State.CANCELLED);
            }
            default -> {}
        }
        return Optional.empty();
    }

    private Optional<Violation> violation(String convId, String reason) {
        log.warn("Subscribe protocol violation [{}]: {}", convId, reason);
        return Optional.of(new Violation(convId, reason));
    }
}
