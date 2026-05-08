package com.agentos.demo.agents;

import com.agentos.demo.SimulatedService;
import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A concrete MobileAgent that wraps a SimulatedService.
 * Validates the migration pipeline: checkpoint, suspend, send, restore.
 */
public class MigratableServiceAgent implements MobileAgent {
    private static final Logger log = LoggerFactory.getLogger(MigratableServiceAgent.class);
    private final AgentId id;
    private SimulatedService service;
    private AgentContext ctx;

    public MigratableServiceAgent(String name, SimulatedService service) {
        this.id = AgentId.of(name);
        this.service = service;
    }

    public SimulatedService service() { return service; }

    @Override public AgentId agentId() { return id; }
    @Override public boolean isEventDriven() { return false; }

    @Override
    public void init(AgentContext ctx) {
        this.ctx = ctx;
    }

    @Override public void step() {}
    @Override public void onMessage(ACLMessage msg) {}
    @Override public void suspend() {}
    @Override public void resume() {}
    @Override public void shutdown() {}

    @Override
    public byte[] checkpoint() {
        var health = service.health();
        String json = String.format(
            "{\"name\":\"%s\",\"status\":\"%s\",\"replicas\":%d,\"cpu\":%.1f}",
            service.name(), health.status(), health.replicas(), health.cpuPercent());
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void restore(byte[] state) {
        String json = new String(state, StandardCharsets.UTF_8);
        String status = extract(json, "status");
        String replicas = extract(json, "replicas");
        if (status != null) {
            log.info("Restored agent {} with status={}, replicas={}", id.name(), status, replicas);
        }
    }

    @Override
    public boolean prepareMigration() {
        log.info("Agent {} preparing for migration", id.name());
        return true;
    }

    @Override
    public void afterMigration(AgentContext newContext) {
        this.ctx = newContext;
        log.info("Agent {} migrated to new container", id.name());
    }

    @Override
    public Map<String, String> migrationMetadata() {
        return Map.of("service", service.name(), "status", service.health().status());
    }

    private static String extract(String json, String key) {
        String s = "\"" + key + "\":\"";
        int i = json.indexOf(s);
        if (i < 0) {
            s = "\"" + key + "\":";
            i = json.indexOf(s);
            if (i < 0) return null;
            i += s.length();
            int e = json.indexOf(",", i);
            if (e < 0) e = json.indexOf("}", i);
            return e > i ? json.substring(i, e).trim() : null;
        }
        i += s.length();
        int e = json.indexOf("\"", i);
        return e > i ? json.substring(i, e) : null;
    }
}
