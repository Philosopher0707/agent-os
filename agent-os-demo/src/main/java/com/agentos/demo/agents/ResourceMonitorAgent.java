package com.agentos.demo.agents;

import com.agentos.demo.SimulatedService;
import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourceMonitorAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(ResourceMonitorAgent.class);
    private static final double CPU_THRESHOLD = 80.0;
    private final AgentId id;
    private final SimulatedService service;
    private final AgentId orchestratorId;
    private AgentContext ctx;

    public ResourceMonitorAgent(String name, SimulatedService service, AgentId orchestratorId) {
        this.id = AgentId.of(name);
        this.service = service;
        this.orchestratorId = orchestratorId;
    }

    @Override public AgentId agentId() { return id; }
    @Override public void init(AgentContext ctx) { this.ctx = ctx; }
    @Override
    public void step() {
        var health = service.health();
        if (health.cpuPercent() > CPU_THRESHOLD) {
            log.info("{} → {} → {} | HIGH_CPU: {} {}%",
                id.name(), "INFORM", orchestratorId.name(), service.name(), health.cpuPercent());
            ctx.send(ACLMessage.builder()
                .performative(ACLMessage.Performative.INFORM)
                .sender(id)
                .receiver(orchestratorId)
                .content(String.format("{\"alert\":\"HIGH_CPU\",\"service\":\"%s\",\"cpu\":%.1f}",
                    service.name(), health.cpuPercent()))
                .build());
        }
    }
    @Override public void onMessage(ACLMessage msg) {}
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() {}
}
