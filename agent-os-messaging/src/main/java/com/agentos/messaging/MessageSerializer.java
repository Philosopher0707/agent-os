package com.agentos.messaging;

import com.agentos.kernel.messaging.ACLMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Optional;

public final class MessageSerializer {
    private static final ObjectMapper mapper = new ObjectMapper()
        .findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static String toJson(ACLMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    public static Optional<ACLMessage> fromJson(String json) {
        try {
            return Optional.of(mapper.readValue(json, ACLMessage.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
