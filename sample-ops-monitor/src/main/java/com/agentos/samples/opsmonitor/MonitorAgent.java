package com.agentos.samples.opsmonitor;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Agent that polls an external API's health endpoint every tick.
 * When it detects a failure, it sends a CFP (Call For Proposal)
 * to the remediation agent to negotiate a fix.
 */
public class MonitorAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(MonitorAgent.class);
    private final AgentId id;
    private final AgentId remediationId;
    private final String healthUrl;
    private AgentContext ctx;

    public MonitorAgent(String name, AgentId remediationId, String healthUrl) {
        this.id = AgentId.of(name);
        this.remediationId = remediationId;
        this.healthUrl = healthUrl;
    }

    @Override public AgentId agentId() { return id; }
    @Override public boolean isEventDriven() { return false; }

    @Override
    public void init(AgentContext ctx) {
        this.ctx = ctx;
        ctx.services().register(new com.agentos.kernel.directory.ServiceDescription(
            id, "monitor", java.util.Map.of("target", healthUrl)));
    }

    @Override
    public void step() {
        try {
            HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 500 || (resp.body() != null && resp.body().contains("DOWN"))) {
                log.warn("{} detected UNHEALTHY API (status={}, body={})",
                    id.name(), resp.statusCode(), resp.body().trim());
                sendRemediationRequest();
            } else {
                log.info("{} API healthy (status={}, body={})",
                    id.name(), resp.statusCode(), resp.body().trim());
            }
        } catch (Exception e) {
            log.error("{} poll failed: {}", id.name(), e.getMessage());
        }
    }

    private void sendRemediationRequest() {
        ACLMessage cfp = ACLMessage.builder()
            .performative(ACLMessage.Performative.CFP)
            .sender(id)
            .receiver(remediationId)
            .content("{\"service\":\"external-api\",\"issue\":\"unhealthy\",\"action\":\"restart\",\"url\":\""
                + healthUrl.replace("/health", "/fix") + "\"}")
            .protocol("fipa-contract-net")
            .build();
        ctx.send(cfp);
        log.info("{} → CFP → {} | negotiate remediation", id.name(), remediationId.name());
    }

    @Override public void onMessage(ACLMessage msg) {
        if (msg.performative() == ACLMessage.Performative.INFORM) {
            log.info("{} received INFORM from {}: {}", id.name(), msg.sender().name(), msg.content());
        } else if (msg.performative() == ACLMessage.Performative.FAILURE) {
            log.error("{} received FAILURE from {}: {}", id.name(), msg.sender().name(), msg.content());
        }
    }
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() { ctx.services().deregister(id, "monitor"); }
}