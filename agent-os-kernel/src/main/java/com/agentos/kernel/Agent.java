package com.agentos.kernel;

import com.agentos.kernel.messaging.ACLMessage;

public interface Agent {
    AgentId agentId();

    default boolean isEventDriven() { return false; }

    void init(AgentContext ctx);

    default void step() {}

    void onMessage(ACLMessage msg);

    void suspend();

    void resume();

    void shutdown();
}
