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
 * Event-driven BDI agent that handles remediation contract negotiation.
 *
 * Protocol flow (Contract-Net):
 *   1. Receive CFP from MonitorAgent
 *   2. Send PROPOSE with plan to fix
 *   3. Receive ACCEPT_PROPOSAL (or REJECT_PROPOSAL)
 *   4. Execute fix by calling external API
 *   5. Send INFORM (success) or FAILURE
 */
public class RemediationAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(RemediationAgent.class);
    private final AgentId id;
    private AgentContext ctx;

    public RemediationAgent(String name) {
        this.id = AgentId.of(name);
    }

    @Override public AgentId agentId() { return id; }
    @Override public boolean isEventDriven() { return true; }
    @Override public void init(AgentContext ctx) { this.ctx = ctx; }

    @Override
    public void onMessage(ACLMessage msg) {
        switch (msg.performative()) {
            case CFP -> handleCfp(msg);
            case ACCEPT_PROPOSAL -> handleAccept(msg);
            case REJECT_PROPOSAL -> handleReject(msg);
            default -> log.debug("{} received {} from {}", id.name(), msg.performative(), msg.sender().name());
        }
    }

    private void handleCfp(ACLMessage msg) {
        log.info("{} received CFP from {}: {}", id.name(), msg.sender().name(), msg.content());
        String fixUrl = extractFixUrl(msg.content());
        if (fixUrl == null) {
            sendReply(msg, ACLMessage.Performative.REFUSE, "{\"reason\":\"no-fix-url\"}");
            return;
        }
        // Bid: offer to fix for free (this is a demo)
        sendReply(msg, ACLMessage.Performative.PROPOSE,
            "{\"service\":\"external-api\",\"action\":\"restart\",\"cost\":0,\"eta\":\"5s\",\"url\":\""
            + fixUrl + "\"}");
        log.info("{} → PROPOSE → {} | ready to fix", id.name(), msg.sender().name());
    }

    private void handleAccept(ACLMessage msg) {
        log.info("{} received ACCEPT from {}", id.name(), msg.sender().name());
        String fixUrl = extractFixUrl(msg.content());
        if (fixUrl == null) {
            sendReply(msg, ACLMessage.Performative.FAILURE,
                "{\"reason\":\"missing-fix-url\"}");
            return;
        }
        try {
            boolean success = callFixEndpoint(fixUrl);
            if (success) {
                sendReply(msg, ACLMessage.Performative.INFORM,
                    "{\"result\":\"fixed\",\"service\":\"external-api\"}");
                log.info("{} → INFORM → {} | fix succeeded", id.name(), msg.sender().name());
            } else {
                sendReply(msg, ACLMessage.Performative.FAILURE,
                    "{\"reason\":\"fix-api-returned-error\"}");
                log.warn("{} → FAILURE → {} | fix API error", id.name(), msg.sender().name());
            }
        } catch (Exception e) {
            sendReply(msg, ACLMessage.Performative.FAILURE,
                "{\"reason\":\"" + e.getMessage() + "\"}");
            log.error("{} → FAILURE → {} | exception: {}", id.name(), msg.sender().name(), e.getMessage());
        }
    }

    private void handleReject(ACLMessage msg) {
        log.info("{} received REJECT from {}", id.name(), msg.sender().name());
    }

    /**
     * Calls the external API's /fix endpoint to remediate the failure.
     */
    private boolean callFixEndpoint(String fixUrl) throws Exception {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(fixUrl))
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200;
    }

    private String extractFixUrl(String content) {
        if (content == null) return null;
        int idx = content.indexOf("\"url\":\"");
        if (idx < 0) return null;
        int start = idx + 7;
        int end = content.indexOf("\"", start);
        return end > 0 ? content.substring(start, end) : null;
    }

    private void sendReply(ACLMessage original, ACLMessage.Performative perf, String body) {
        ACLMessage reply = ACLMessage.builder()
            .performative(perf)
            .sender(id)
            .receiver(original.sender())
            .conversationId(original.conversationId())
            .protocol(original.protocol())
            .content(body)
            .build();
        ctx.send(reply);
    }

    @Override public void step() {}
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() {}
}
