package com.agentos.kernel.messaging;

import com.agentos.kernel.AgentId;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public record ACLMessage(
    Performative performative,
    AgentId sender,
    Set<AgentId> receivers,
    AgentId replyTo,
    String conversationId,
    String replyWith,
    String protocol,
    String language,
    String encoding,
    String ontology,
    String content,
    Instant timestamp
) {
    public enum Performative {
        ACCEPT_PROPOSAL, AGREE, CANCEL, CFP, CONFIRM, DISCONFIRM,
        FAILURE, INFORM, INFORM_IF, INFORM_REF, NOT_UNDERSTOOD,
        PROPOSE, QUERY_IF, QUERY_REF, REFUSE, REJECT_PROPOSAL,
        REQUEST, REQUEST_WHEN, REQUEST_WHENEVER, SUBSCRIBE,
        PROXY, PROPAGATE
    }

    public ACLMessage {
        if (performative == null) throw new IllegalArgumentException("performative must not be null");
        if (sender == null) throw new IllegalArgumentException("sender must not be null");
        if (receivers == null || receivers.isEmpty())
            throw new IllegalArgumentException("at least one receiver required");
        receivers = Set.copyOf(receivers);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Performative performative;
        private AgentId sender;
        private final Set<AgentId> receivers = new HashSet<>();
        private AgentId replyTo;
        private String conversationId = UUID.randomUUID().toString();
        private String replyWith;
        private String protocol;
        private String language = "json";
        private String encoding = "UTF-8";
        private String ontology;
        private String content;

        public Builder performative(Performative p) { this.performative = p; return this; }
        public Builder sender(AgentId s) { this.sender = s; return this; }
        public Builder receiver(AgentId r) { this.receivers.add(r); return this; }
        public Builder receivers(Set<AgentId> rs) { this.receivers.addAll(rs); return this; }
        public Builder replyTo(AgentId r) { this.replyTo = r; return this; }
        public Builder conversationId(String c) { this.conversationId = c; return this; }
        public Builder replyWith(String r) { this.replyWith = r; return this; }
        public Builder protocol(String p) { this.protocol = p; return this; }
        public Builder language(String l) { this.language = l; return this; }
        public Builder encoding(String e) { this.encoding = e; return this; }
        public Builder ontology(String o) { this.ontology = o; return this; }
        public Builder content(String c) { this.content = c; return this; }

        public ACLMessage build() {
            return new ACLMessage(performative, sender, receivers, replyTo, conversationId,
                replyWith, protocol, language, encoding, ontology, content, Instant.now());
        }
    }
}
