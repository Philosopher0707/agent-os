package com.agentos.reasoning.reactive;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.reasoning.Behavior;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class ReactiveReasoningEngineTest {
    @Test
    void shouldDispatchToMatchingBehavior() {
        var engine = new ReactiveReasoningEngine();
        var agent = new TestAgent("test");
        engine.install(agent);

        var received = new CompletableFuture<String>();
        engine.addBehavior(agent, new Behavior() {
            @Override public boolean matches(ACLMessage msg) { return msg.performative() == ACLMessage.Performative.INFORM; }
            @Override public CompletionStage<Void> handle(ACLMessage msg, AgentContext ctx) {
                received.complete(msg.content());
                return CompletableFuture.completedFuture(null);
            }
        });

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("s"))
            .receiver(AgentId.of("r"))
            .content("hello")
            .build();
        engine.onMessage(agent, msg);
        assertThat(received).isCompletedWithValue("hello");
    }

    @Test
    void shouldFallbackToAgentOnNoMatch() {
        var engine = new ReactiveReasoningEngine();
        var received = new CompletableFuture<String>();
        Agent agent = new Agent() {
            @Override public AgentId agentId() { return AgentId.of("test"); }
            @Override public boolean isEventDriven() { return true; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) { received.complete(msg.content()); }
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
        engine.install(agent);
        engine.addBehavior(agent, new NoMatchBehavior());

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.REQUEST)
            .sender(AgentId.of("s"))
            .receiver(AgentId.of("r"))
            .content("direct")
            .build();
        engine.onMessage(agent, msg);
        assertThat(received).isCompletedWithValue("direct");
    }

    static class TestAgent implements Agent {
        private final AgentId id;
        TestAgent(String name) { this.id = AgentId.of(name); }
        @Override public AgentId agentId() { return id; }
        @Override public void init(AgentContext ctx) {}
        @Override public void onMessage(ACLMessage msg) {}
        @Override public void suspend() {}
        @Override public void resume() {}
        @Override public void shutdown() {}
    }

    static class NoMatchBehavior implements Behavior {
        @Override public boolean matches(ACLMessage msg) { return false; }
        @Override public CompletionStage<Void> handle(ACLMessage msg, AgentContext ctx) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
