package com.agentos.demo.agents;

import com.agentos.demo.SimulatedService;
import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HealthCheckerAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(HealthCheckerAgent.class);
    private final AgentId id;
    private final SimulatedService service;
    private final AgentId orchestratorId;
    private AgentContext ctx;

    public HealthCheckerAgent(String name, SimulatedService service, AgentId orchestratorId) {
        this.id = AgentId.of(name);
        this.service = service;
        this.orchestratorId = orchestratorId;
    }

    @Override public AgentId agentId() { return id; }

    @Override
    public void init(AgentContext ctx) {
        this.ctx = ctx;
        ctx.services().register(new com.agentos.kernel.directory.ServiceDescription(id, "health-check",
            java.util.Map.of("service", service.name())));
    }

    @Override
    public void step() {
        var health = service.health();
        log.info("{} → {} → {} | status={} cpu={}%",
            id.name(), "INFORM", orchestratorId.name(), health.status(), health.cpuPercent());
        ACLMessage msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(id)
            .receiver(orchestratorId)
            .content(String.format("{\"service\":\"%s\",\"status\":\"%s\",\"cpu\":%.1f}",
                service.name(), health.status(), health.cpuPercent()))
            .build();
        ctx.send(msg);
    }

    @Override public void onMessage(ACLMessage msg) {}
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() { ctx.services().deregister(id, "health-check"); }
}
