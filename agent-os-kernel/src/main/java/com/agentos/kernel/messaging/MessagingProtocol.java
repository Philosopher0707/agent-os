package com.agentos.kernel.messaging;

import java.util.Optional;

public interface MessagingProtocol {
    record Violation(String conversationId, String reason) {}

    Optional<Violation> validate(String conversationId, ACLMessage incomingMessage);
}
