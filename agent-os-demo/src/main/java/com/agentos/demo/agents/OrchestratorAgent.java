package com.agentos.demo.agents;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrchestratorAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private final AgentId id = AgentId.of("orchestrator");
    private AgentContext ctx;
    private AgentId serviceManagerId;

    @Override public AgentId agentId() { return id; }
    @Override public boolean isEventDriven() { return true; }

    @Override
    public void init(AgentContext ctx) {
        this.ctx = ctx;
        var found = ctx.services().search("service-manager");
        if (!found.isEmpty()) serviceManagerId = found.get(0).provider();
    }

    @Override
    public void onMessage(ACLMessage msg) {
        if (msg.performative() == ACLMessage.Performative.INFORM) handleInform(msg);
        else if (msg.performative() == ACLMessage.Performative.PROPOSE) handlePropose(msg);
    }

    private void handleInform(ACLMessage msg) {
        String content = msg.content();
        if (content == null) return;
        if (content.contains("HIGH_CPU") || content.contains("DEGRADED")) {
            String serviceName = extractValue(content, "service");
            log.info("{} → CFP → {} | scale-needed {}", id.name(),
                serviceManagerId != null ? serviceManagerId.name() : "?", serviceName);
            ctx.send(ACLMessage.builder()
                .performative(ACLMessage.Performative.CFP).sender(id).receiver(serviceManagerId)
                .protocol("fipa-contract-net")
                .content(String.format("{\"service\":\"%s\",\"issue\":\"high-cpu\"}", serviceName))
                .build());
        } else if (content.contains("SCALE_DONE") || content.contains("restart") && content.contains("ok")) {
            log.info("{} recovery complete: {}", id.name(), content);
        }
    }

    private void handlePropose(ACLMessage msg) {
        String content = msg.content();
        if (content == null) return;
        if (content.contains("scale") || content.contains("restart")) {
            log.info("{} → ACCEPT → {}", id.name(), msg.sender().name());
            ctx.send(ACLMessage.builder()
                .performative(ACLMessage.Performative.ACCEPT_PROPOSAL).sender(id)
                .receiver(msg.sender()).conversationId(msg.conversationId())
                .protocol(msg.protocol()).content(content).build());
        } else if (content.contains("REFUSE")) {
            ctx.send(ACLMessage.builder()
                .performative(ACLMessage.Performative.INFORM).sender(id)
                .receiver(AgentId.of("alert-dispatcher"))
                .content("{\"alert\":\"escalation\"}").build());
        }
    }

    private static String extractValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "unknown";
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : "unknown";
    }

    @Override public void step() {}
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() {}
}
