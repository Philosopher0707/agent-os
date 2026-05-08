package com.agentos.samples.opsmonitor;

import com.agentos.directory.InMemoryAgentRegistry;
import com.agentos.directory.InMemoryServiceDirectory;
import com.agentos.kernel.*;
import com.agentos.kernel.impl.DefaultAgentKernel;
import com.agentos.messaging.LocalMessageTransport;
import com.agentos.reasoning.bdi.AslParser;
import com.agentos.reasoning.bdi.BdiReasoningEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates Agent OS monitoring and remediating an external HTTP API.
 *
 * Scenario:
 *   1. An ExternalApiSimulator is started (port auto-assigned)
 *   2. Every 2 seconds, MonitorAgent polls the /health endpoint
 *   3. After 5 healthy polls, the API begins returning 503
 *   4. MonitorAgent detects the failure and sends a CFP to RemediationAgent
 *   5. RemediationAgent proposes a fix, is accepted, calls POST /fix
 *   6. API recovers. MonitorAgent confirms health on next poll.
 *
 * This demonstrates:
 *   - Real HTTP integration (polling + remedation)
 *   - FIPA Contract-Net protocol (CFP → PROPOSE → ACCEPT → INFORM)
 *   - BDI reasoning (Declarative plans for agent behavior)
 *   - Service discovery (agents register in ServiceDirectory)
 *   - Management API (health/metrics on :9095)
 */
public class OpsMonitorDemo {
    private static final Logger log = LoggerFactory.getLogger(OpsMonitorDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("═══ Agent OS — Ops Monitor Demo ═══");

        // ─── 1. Start simulated external API ───
        ExternalApiSimulator api = new ExternalApiSimulator(0, 8, 999999);
        api.start();
        String healthUrl = "http://localhost:" + api.port() + "/health";
        log.info("External API: {}", healthUrl);

        // ─── 2. Create Agent OS kernel ───
        var config = new AgentOsConfig(
            Duration.ofSeconds(2),   // tickInterval
            Duration.ofSeconds(5),     // stepTimeout
            1000,                      // mailboxCapacity
            3,                         // maxRetries
            Duration.ofSeconds(3),     // gracePeriod
            5,                         // consecutiveFailureLimit
            Duration.ofSeconds(30),    // gracefulShutdown
            Duration.ofSeconds(10),    // evictionCheckInterval
            9095,                      // managementPort
            10000,                     // maxMessageSize
            null,                      // stateStore
            3600,                      // messageRetentionSeconds
            false,                     // sandboxEnabled
            "default"                  // sandboxPolicy
        );

        AgentKernel kernel = DefaultAgentKernel.create("ops-monitor", config);
        var bdi = new BdiReasoningEngine();
        kernel.bind(bdi);
        kernel.bind(new LocalMessageTransport());
        kernel.bind(new InMemoryAgentRegistry());
        kernel.bind(new InMemoryServiceDirectory());
        kernel.start();
        log.info("Agent OS kernel started on management port {}", config.managementPort());

        // ─── 3. Register agents ───
        AgentId remediationId = AgentId.of("remediation-agent");
        AgentId monitorId = AgentId.of("monitor-agent");

        var remediationAgent = new RemediationAgent("remediation-agent");
        var monitorAgent = new MonitorAgent("monitor-agent", remediationId, healthUrl);

        kernel.register(remediationAgent);
        kernel.register(monitorAgent);

        // Give BDIBDI engine plans for the remediation agent (optional — RemediationAgent handles
        // messages directly, but we can add BDI plans for additional behavior)
        // bdi.install(remediationAgent);
        // bdi.library(remediationAgent).addAll(AslParser.parse("""
        // +cfp(external-api) : true <- .println("CFP received for external-api").
        // """));

        log.info("Agents registered: monitor={}, remediation={}", monitorId.name(), remediationId.name());
        log.info("Management endpoint: http://localhost:{}/health", config.managementPort());
        log.info("Polling every {} seconds. Watching for failure + remediation...", config.tickInterval().getSeconds());

        int durationSeconds = args.length > 0 ? Integer.parseInt(args[0]) : 25;
        log.info("Demo duration: {} seconds", durationSeconds);

        // ─── 4. Run the demo ───
        Thread.sleep(durationSeconds * 1000L);

        log.info("═══ Demo complete ═══");
        log.info("API status: failing={}", api.isFailing());

        kernel.close();
        api.close();
    }
}
