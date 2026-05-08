package com.agentos.kernel.impl;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class DefaultAgentKernelTest {
    private DefaultAgentKernel kernel;

    @BeforeEach
    void setUp() {
        kernel = (DefaultAgentKernel) DefaultAgentKernel.createDefault();
        kernel.start();
    }

    @AfterEach
    void tearDown() {
        kernel.close();
    }

    @Test
    void shouldRegisterAgentAndTransitionToActive() {
        var ref = new java.util.concurrent.atomic.AtomicReference<String>();
        var agent = createTestAgent("test-1", msg -> ref.set(msg.content()));
        AgentId id = kernel.register(agent);
        assertThat(kernel.stateOf(id)).isEqualTo(AgentLifecycle.ACTIVE);
    }

    @Test
    void shouldSendMessageBetweenAgents() throws Exception {
        var received = new LinkedBlockingQueue<ACLMessage>(1);
        var receiver = createTestAgent("receiver", received::offer);

        AgentId receiverId = kernel.register(receiver);

        ACLMessage msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("external"))
            .receiver(receiverId)
            .content("hello world")
            .build();
        kernel.send(msg);

        ACLMessage delivered = received.poll(2, TimeUnit.SECONDS);
        assertThat(delivered).isNotNull();
        assertThat(delivered.content()).isEqualTo("hello world");
    }

    @Test
    void shouldHandleEventDrivenAgent() throws Exception {
        var called = new AtomicBoolean(false);
        Agent agent = new Agent() {
            @Override public AgentId agentId() { return AgentId.of("event-driven"); }
            @Override public boolean isEventDriven() { return true; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) { called.set(true); }
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
        AgentId id = kernel.register(agent);
        assertThat(kernel.stateOf(id)).isEqualTo(AgentLifecycle.ACTIVE);

        kernel.send(ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("x"))
            .receiver(id)
            .content("ping")
            .build());

        Thread.sleep(200);
        assertThat(called.get()).isTrue();
    }

    @Test
    void healthShouldReportStats() {
        kernel.register(createTestAgent("a1", m -> {}));
        kernel.register(createTestAgent("a2", m -> {}));
        var health = kernel.health();
        assertThat(health.activeAgents()).isEqualTo(2);
        assertThat(health.terminatedAgents()).isEqualTo(0);
    }

    @Test
    void shouldReturnTerminatedForUnknownAgent() {
        assertThat(kernel.stateOf(AgentId.of("nonexistent")))
            .isEqualTo(AgentLifecycle.TERMINATED);
    }

    @Test
    void transitionShouldChangeState() {
        var agent = createTestAgent("stateful", m -> {});
        AgentId id = kernel.register(agent);
        kernel.transition(id, AgentLifecycle.SUSPENDED);
        assertThat(kernel.stateOf(id)).isEqualTo(AgentLifecycle.SUSPENDED);
    }

    @Test
    void unregisterShouldRemoveAgent() {
        var agent = createTestAgent("temporary", m -> {});
        AgentId id = kernel.register(agent);
        kernel.unregister(id);
        assertThat(kernel.stateOf(id)).isEqualTo(AgentLifecycle.TERMINATED);
    }

    private static Agent createTestAgent(String name, java.util.function.Consumer<ACLMessage> onMsg) {
        AgentId id = AgentId.of(name);
        return new Agent() {
            @Override public AgentId agentId() { return id; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) { onMsg.accept(msg); }
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
    }
}
