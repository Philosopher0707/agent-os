package com.agentos.kernel;

import java.util.UUID;

public record AgentId(String name, UUID id) {

    public AgentId {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
    }

    public static AgentId of(String name) {
        return new AgentId(name, UUID.randomUUID());
    }

    @Override
    public String toString() {
        return name + "[" + id.toString().substring(0, 8) + "]";
    }
}
