package com.agentos.kernel;

public class AgentExistsException extends RuntimeException {
    public AgentExistsException(String name) {
        super("Agent with name '" + name + "' already exists");
    }
}
