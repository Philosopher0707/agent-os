package com.agentos.demo.agents;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlertDispatcherAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(AlertDispatcherAgent.class);
    private final AgentId id = AgentId.of("alert-dispatcher");

    @Override public AgentId agentId() { return id; }
    @Override public boolean isEventDriven() { return true; }
    @Override public void init(AgentContext ctx) {}
    @Override
    public void onMessage(ACLMessage msg) {
        log.warn("ALERT: {} from {}: {}", msg.performative(), msg.sender().name(), msg.content());
    }
    @Override public void step() {}
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() {}
}
