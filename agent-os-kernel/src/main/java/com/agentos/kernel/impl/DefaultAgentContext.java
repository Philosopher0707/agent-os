package com.agentos.kernel.impl;

import com.agentos.kernel.*;
import com.agentos.kernel.directory.*;
import com.agentos.kernel.messaging.ACLMessage;
import java.util.concurrent.ScheduledExecutorService;

final class DefaultAgentContext implements AgentContext {
    private final AgentId self;
    private final AgentRegistry registry;
    private final ServiceDirectory services;
    private final java.util.function.Consumer<ACLMessage> sender;
    private final ScheduledExecutorService scheduler;

    DefaultAgentContext(AgentId self, AgentRegistry registry, ServiceDirectory services,
                       java.util.function.Consumer<ACLMessage> sender,
                       ScheduledExecutorService sharedScheduler) {
        this.self = self;
        this.registry = registry;
        this.services = services;
        this.sender = sender;
        this.scheduler = sharedScheduler;
    }

    @Override public AgentId self() { return self; }
    @Override public AgentRegistry registry() { return registry; }
    @Override public ServiceDirectory services() { return services; }
    @Override public void send(ACLMessage msg) { sender.accept(msg); }
    @Override public ScheduledExecutorService scheduler() { return scheduler; }
}
