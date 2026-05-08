package com.agentos.kernel.messaging;

import com.agentos.kernel.AgentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates FIPA Contract-Net protocol conversations.
 * Tracks conversation state and ensures messages follow the protocol sequence.
 */
public final class ContractNetProtocol implements MessagingProtocol {
    private static final Logger log = LoggerFactory.getLogger(ContractNetProtocol.class);

    private final Map<String, ConversationState> conversations = new ConcurrentHashMap<>();

    private enum State {
        INITIATED,      // CFP sent
        PROPOSED,       // Proposal received
        ACCEPTED,       // Proposal accepted
        REJECTED,       // Proposal rejected
        COMPLETED,      // Action completed
        FAILED          // Failure
    }

    private record ConversationState(State state, AgentId initiator, AgentId participant) {}

    @Override
    public Optional<Violation> validate(String conversationId, ACLMessage msg) {
        if (conversationId == null) return Optional.empty();
        if (!"fipa-contract-net".equals(msg.protocol())) return Optional.empty();

        var perf = msg.performative();
        var current = conversations.get(conversationId);

        switch (perf) {
            case CFP -> {
                if (current != null) {
                    return violation(conversationId, "CFP already sent for this conversation");
                }
                conversations.put(conversationId,
                    new ConversationState(State.INITIATED, msg.sender(), null));
            }
            case PROPOSE -> {
                if (current == null) {
                    return violation(conversationId, "PROPOSE without prior CFP");
                }
                if (current.state() != State.INITIATED) {
                    return violation(conversationId, "PROPOSE at wrong state: " + current.state());
                }
                conversations.put(conversationId,
                    new ConversationState(State.PROPOSED, current.initiator(), msg.sender()));
            }
            case REFUSE -> {
                if (current == null) {
                    return violation(conversationId, "REFUSE without prior CFP");
                }
                conversations.put(conversationId,
                    new ConversationState(State.REJECTED, current.initiator(), msg.sender()));
            }
            case ACCEPT_PROPOSAL -> {
                if (current == null) {
                    return violation(conversationId, "ACCEPT_PROPOSAL without prior PROPOSE");
                }
                if (current.state() != State.PROPOSED) {
                    return violation(conversationId, "ACCEPT_PROPOSAL at wrong state: " + current.state());
                }
                conversations.put(conversationId,
                    new ConversationState(State.ACCEPTED, current.initiator(), current.participant()));
            }
            case REJECT_PROPOSAL -> {
                if (current == null) {
                    return violation(conversationId, "REJECT_PROPOSAL without prior PROPOSE");
                }
                conversations.put(conversationId,
                    new ConversationState(State.REJECTED, current.initiator(), current.participant()));
            }
            case INFORM -> {
                if (current != null && current.state() == State.ACCEPTED) {
                    conversations.put(conversationId,
                        new ConversationState(State.COMPLETED, current.initiator(), current.participant()));
                }
                // INFORM can also be used outside contract-net
            }
            case FAILURE -> {
                if (current != null) {
                    conversations.put(conversationId,
                        new ConversationState(State.FAILED, current.initiator(), current.participant()));
                }
            }
            default -> {
                // Other performatives not part of contract-net
            }
        }

        return Optional.empty();
    }

    private Optional<Violation> violation(String convId, String reason) {
        log.warn("Contract-Net violation [{}]: {}", convId, reason);
        return Optional.of(new Violation(convId, reason));
    }

    /** Clean up completed/failed conversations */
    public void cleanup() {
        conversations.entrySet().removeIf(e -> {
            var s = e.getValue().state();
            return s == State.COMPLETED || s == State.FAILED || s == State.REJECTED;
        });
    }
}
