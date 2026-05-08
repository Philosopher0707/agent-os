package com.agentos.reasoning.llm;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class LlmReasoningEngineTest {

    @Test
    void shouldDispatchToToolsBasedOnLlmResponse() {
        Function<String, String> mockLlm = prompt -> "send_message";
        var engine = new LlmReasoningEngine(mockLlm);
        var agent = simpleAgent("coordinator");
        var ctx = new StubContext();
        engine.start(agent, ctx);

        var toolCalled = new CompletableFuture<String>();
        engine.addTool(agent, new LlmAgentTool() {
            @Override public String name() { return "send_message"; }
            @Override public String description() { return "send FIPA-ACL message"; }
            @Override public String execute(String input, AgentContext ctx) {
                toolCalled.complete(input);
                return "ok";
            }
        });

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("rm"))
            .receiver(AgentId.of("coordinator"))
            .content("{\"alert\":\"HIGH_CPU\",\"service\":\"payment\"}")
            .build();
        engine.onMessage(agent, msg);

        assertThat(toolCalled).isCompleted();
    }

    @Test
    void shouldNotFailOnLlmError() {
        Function<String, String> failingLlm = prompt -> { throw new RuntimeException("API error"); };
        var engine = new LlmReasoningEngine(failingLlm);
        var agent = simpleAgent("coordinator");
        engine.start(agent, new StubContext());

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("rm"))
            .receiver(AgentId.of("coordinator"))
            .content("test")
            .build();
        engine.onMessage(agent, msg);
    }

    @Test
    void shouldAddTools() {
        var engine = new LlmReasoningEngine((Function<String, String>) prompt -> "");
        var agent = simpleAgent("a");
        engine.addTool(agent, new LlmAgentTool() {
            @Override public String name() { return "restart_service"; }
            @Override public String description() { return "restart"; }
            @Override public String execute(String input, AgentContext ctx) { return "done"; }
        });
    }

    private Agent simpleAgent(String name) {
        AgentId id = AgentId.of(name);
        return new Agent() {
            @Override public AgentId agentId() { return id; }
            @Override public boolean isEventDriven() { return true; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
    }

    static class StubContext implements AgentContext {
        @Override public AgentId self() { return AgentId.of("test"); }
        @Override public com.agentos.kernel.directory.AgentRegistry registry() { return null; }
        @Override public com.agentos.kernel.directory.ServiceDirectory services() {
            return new com.agentos.kernel.directory.ServiceDirectory() {
                @Override public void register(com.agentos.kernel.directory.ServiceDescription s) {}
                @Override public void deregister(AgentId p, String t) {}
                @Override public java.util.List<com.agentos.kernel.directory.ServiceDescription> search(String t) { return java.util.List.of(); }
                @Override public java.util.List<com.agentos.kernel.directory.ServiceDescription> search(String t, java.util.function.Predicate<java.util.Map<String,String>> c) { return java.util.List.of(); }
                @Override public com.agentos.kernel.directory.SubscriptionToken subscribe(String t, java.util.function.Consumer<java.util.List<com.agentos.kernel.directory.ServiceDescription>> cb) { return null; }
                @Override public void unsubscribe(com.agentos.kernel.directory.SubscriptionToken t) {}
            };
        }
        @Override public void send(ACLMessage msg) {}
        @Override public ScheduledExecutorService scheduler() { return Executors.newSingleThreadScheduledExecutor(); }
    }
}
