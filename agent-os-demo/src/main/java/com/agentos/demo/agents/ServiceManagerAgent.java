package com.agentos.demo.agents;

import com.agentos.demo.SimulatedService;
import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public class ServiceManagerAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(ServiceManagerAgent.class);
    private final AgentId id = AgentId.of("service-manager");
    private final Map<String, SimulatedService> services;
    private AgentContext ctx;

    public ServiceManagerAgent(Map<String, SimulatedService> services) { this.services = services; }

    @Override public AgentId agentId() { return id; }
    @Override public boolean isEventDriven() { return true; }

    @Override
    public void init(AgentContext ctx) {
        this.ctx = ctx;
        ctx.services().register(new com.agentos.kernel.directory.ServiceDescription(id, "service-manager", Map.of()));
    }

    @Override
    public void onMessage(ACLMessage msg) {
        if (msg.performative() == ACLMessage.Performative.CFP) handleCfp(msg);
        else if (msg.performative() == ACLMessage.Performative.ACCEPT_PROPOSAL) handleAccept(msg);
    }

    private void handleCfp(ACLMessage msg) {
        String serviceName = extractServiceName(msg.content());
        if (serviceName == null || !services.containsKey(serviceName)) {
            sendReply(msg, ACLMessage.Performative.REFUSE, "{\"reason\":\"unknown service\"}");
            return;
        }
        var svc = services.get(serviceName);
        var health = svc.health();
        if (health.cpuPercent() > 80) {
            sendReply(msg, ACLMessage.Performative.PROPOSE, "{\"service\":\"" + serviceName + "\",\"action\":\"scale\",\"delta\":2}");
        } else if (health.status().equals("DOWN")) {
            sendReply(msg, ACLMessage.Performative.PROPOSE, "{\"service\":\"" + serviceName + "\",\"action\":\"restart\"}");
        }
    }

    private void handleAccept(ACLMessage msg) {
        String serviceName = extractServiceName(msg.content());
        if (serviceName == null || !services.containsKey(serviceName)) return;
        var svc = services.get(serviceName);
        String content = msg.content();
        if (content != null && content.contains("scale")) {
            svc.scale(2);
            log.info("{} → INFORM → {} | SCALE_DONE: {} -> {}", id.name(), msg.sender().name(), svc.name(), svc.health().replicas());
            sendReply(msg, ACLMessage.Performative.INFORM, String.format("{\"service\":\"%s\",\"action\":\"scale\",\"result\":\"ok\",\"replicas\":%d}", svc.name(), svc.health().replicas()));
        } else if (content != null && content.contains("restart")) {
            svc.restart();
            log.info("{} → INFORM → {} | RESTART_DONE: {}", id.name(), msg.sender().name(), svc.name());
            sendReply(msg, ACLMessage.Performative.INFORM, String.format("{\"service\":\"%s\",\"action\":\"restart\",\"result\":\"ok\"}", svc.name()));
        }
    }

    private void sendReply(ACLMessage original, ACLMessage.Performative perf, String content) {
        ctx.send(ACLMessage.builder()
            .performative(perf).sender(id).receiver(original.sender())
            .conversationId(original.conversationId()).protocol(original.protocol()).content(content).build());
    }

    private String extractServiceName(String content) {
        if (content == null) return null;
        for (String name : services.keySet()) if (content.contains(name)) return name;
        return null;
    }

    @Override public void step() {}
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() { ctx.services().deregister(id, "service-manager"); }
}
