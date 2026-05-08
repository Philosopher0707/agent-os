package com.agentos.kernel.impl;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class AgentSession {
    private static final Logger log = LoggerFactory.getLogger(AgentSession.class);

    private final Agent agent;
    private final AgentContext context;
    private final AgentOsConfig config;
    private volatile AgentLifecycle state = AgentLifecycle.INITIATED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private ScheduledFuture<?> tickFuture;

    AgentSession(Agent agent, AgentContext context, AgentOsConfig config) {
        this.agent = agent;
        this.context = context;
        this.config = config;
    }

    Agent agent() { return agent; }
    AgentId id() { return agent.agentId(); }
    AgentLifecycle state() { return state; }
    AgentContext context() { return context; }
    int consecutiveFailures() { return consecutiveFailures.get(); }
    void resetFailures() { consecutiveFailures.set(0); }

    void init() {
        try {
            agent.init(context);
            transition(AgentLifecycle.ACTIVE);
        } catch (Exception e) {
            log.warn("Agent {} init() threw: {}", agent.agentId().name(), e.getMessage());
            throw new RuntimeException("init failed", e);
        }
    }

    void startTicking(ScheduledExecutorService scheduler, Duration tickInterval) {
        if (agent.isEventDriven()) return;
        tickFuture = scheduler.scheduleAtFixedRate(
            this::tick, 0, tickInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            agent.step();
            consecutiveFailures.set(0);
        } catch (Exception e) {
            log.warn("Agent {} step() threw: {}", agent.agentId().name(), e.getMessage());
            int failures = consecutiveFailures.incrementAndGet();
            if (failures >= config.consecutiveFailureLimit()) {
                log.error("Agent {} exceeded failure limit ({}), transitioning to TRANSIENT",
                    agent.agentId().name(), config.consecutiveFailureLimit());
                transition(AgentLifecycle.TRANSIENT);
            }
        }
    }

    void transition(AgentLifecycle target) {
        log.info("Lifecycle: {} {} -> {}", agent.agentId().name(), state, target);
        this.state = target;
    }

    void suspend() {
        transition(AgentLifecycle.SUSPENDED);
        agent.suspend();
    }

    void resume() {
        agent.resume();
        transition(AgentLifecycle.ACTIVE);
    }

    void shutdown() {
        if (tickFuture != null) {
            tickFuture.cancel(false);
        }
        try {
            agent.shutdown();
        } catch (Exception e) {
            log.warn("Agent {} shutdown() threw: {}", agent.agentId().name(), e.getMessage());
        }
        transition(AgentLifecycle.TERMINATED);
    }

    byte[] serializeState() {
        if (agent instanceof MobileAgent mobile) {
            try {
                return mobile.checkpoint();
            } catch (Exception e) {
                log.warn("Agent {} checkpoint failed: {}", agent.agentId().name(), e.getMessage());
            }
        }
        // Fallback: serialize basic identifying info as JSON
        return ("{\"name\":\"" + agent.agentId().name()
            + "\",\"id\":\"" + agent.agentId().id() + "\"}").getBytes(StandardCharsets.UTF_8);
    }
}
