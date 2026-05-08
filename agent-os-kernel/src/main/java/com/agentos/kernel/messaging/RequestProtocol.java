package com.agentos.kernel.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates FIPA Request protocol conversations.
 * Simple two-step: REQUEST → AGREE/REFUSE → INFORM(Result)/FAILURE.
 */
public final class RequestProtocol implements MessagingProtocol {
    private static final Logger log = LoggerFactory.getLogger(RequestProtocol.class);

    private final Map<String, State> conversations = new ConcurrentHashMap<>();

    private enum State { REQUESTED, AGREED, REFUSED, COMPLETED, FAILED }

    @Override
    public Optional<Violation> validate(String conversationId, ACLMessage msg) {
        if (conversationId == null) return Optional.empty();
        if (!"fipa-request".equals(msg.protocol())) return Optional.empty();

        var perf = msg.performative();
        var current = conversations.get(conversationId);

        switch (perf) {
            case REQUEST -> {
                if (current != null) return violation(conversationId, "duplicate REQUEST");
                conversations.put(conversationId, State.REQUESTED);
            }
            case AGREE -> {
                if (current != State.REQUESTED) return violation(conversationId, "AGREE without REQUEST");
                conversations.put(conversationId, State.AGREED);
            }
            case REFUSE -> {
                if (current != State.REQUESTED) return violation(conversationId, "REFUSE without REQUEST");
                conversations.put(conversationId, State.REFUSED);
            }
            case INFORM -> {
                if (current == State.AGREED) conversations.put(conversationId, State.COMPLETED);
            }
            case FAILURE -> {
                if (current != null) conversations.put(conversationId, State.FAILED);
            }
            default -> {}
        }
        return Optional.empty();
    }

    private Optional<Violation> violation(String convId, String reason) {
        log.warn("Request protocol violation [{}]: {}", convId, reason);
        return Optional.of(new Violation(convId, reason));
    }
}
