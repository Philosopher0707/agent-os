package com.agentos.kernel.impl;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AgentSessionTest {

    private static AgentContext dummyContext() {
        return new AgentContext() {
            @Override public AgentId self() { return AgentId.of("dummy"); }
            @Override public com.agentos.kernel.directory.AgentRegistry registry() { return null; }
            @Override public com.agentos.kernel.directory.ServiceDirectory services() { return null; }
            @Override public void send(ACLMessage msg) {}
            @Override public java.util.concurrent.ScheduledExecutorService scheduler() {
                return java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            }
        };
    }

    @Test
    void shouldStartInitiatedThenGoActive() {
        Agent agent = new Agent() {
            @Override public AgentId agentId() { return AgentId.of("test"); }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
        AgentSession session = new AgentSession(agent, dummyContext(), AgentOsConfig.defaults());
        assertThat(session.state()).isEqualTo(AgentLifecycle.INITIATED);
        session.init();
        assertThat(session.state()).isEqualTo(AgentLifecycle.ACTIVE);
    }

    @Test
    void shouldBeEventDrivenAndSkipTicking() {
        Agent agent = new Agent() {
            @Override public AgentId agentId() { return AgentId.of("test"); }
            @Override public boolean isEventDriven() { return true; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
        AgentSession session = new AgentSession(agent, dummyContext(), AgentOsConfig.defaults());
        session.init();
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        session.startTicking(scheduler, Duration.ofMillis(50));
        scheduler.shutdown();
    }

    @Test
    void shouldTransitionToTerminatedOnShutdown() {
        Agent agent = new Agent() {
            @Override public AgentId agentId() { return AgentId.of("test"); }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
        AgentSession session = new AgentSession(agent, dummyContext(), AgentOsConfig.defaults());
        session.init();
        session.shutdown();
        assertThat(session.state()).isEqualTo(AgentLifecycle.TERMINATED);
    }

    @Test
    void shouldTrackConsecutiveFailures() throws Exception {
        Agent agent = new Agent() {
            @Override public AgentId agentId() { return AgentId.of("test"); }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void step() { throw new RuntimeException("bang"); }
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
        AgentSession session = new AgentSession(agent, dummyContext(), AgentOsConfig.defaults());
        session.init();
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        session.startTicking(scheduler, Duration.ofMillis(10));
        Thread.sleep(100);
        assertThat(session.consecutiveFailures()).isGreaterThan(0);
        scheduler.shutdown();
    }
}
