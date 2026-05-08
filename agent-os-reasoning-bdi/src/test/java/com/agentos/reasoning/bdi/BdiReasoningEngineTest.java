package com.agentos.reasoning.bdi;

import com.agentos.kernel.*;
import com.agentos.kernel.directory.*;
import com.agentos.kernel.messaging.ACLMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.*;

class BdiReasoningEngineTest {

    @Test
    void shouldAddBeliefOnHighCpuMessage() {
        var engine = new BdiReasoningEngine();
        var agent = simpleAgent("monitor");
        engine.start(agent, new StubContext());

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("rm"))
            .receiver(AgentId.of("monitor"))
            .content("{\"alert\":\"HIGH_CPU\",\"service\":\"payment\",\"cpu\":92.0}")
            .build();
        engine.onMessage(agent, msg);

        assertThat(engine.beliefs(agent).holds(Literal.of("alert", "high_cpu", "payment"))).isTrue();
        assertThat(engine.goals(agent).pendingAchievementGoals()).isNotEmpty();
    }

    @Test
    void shouldFirePlanOnGoal() {
        var engine = new BdiReasoningEngine();
        var agent = simpleAgent("orchestrator");
        var ctx = new StubContext();
        engine.start(agent, ctx);

        String plans = "+alert(high_cpu,payment) : true <- .println(\"alert received\").";
        engine.library(agent).addAll(AslParser.parse(plans));
        engine.beliefs(agent).add(Literal.of("alert", "high_cpu", "payment"));
        engine.goals(agent).addAchievementGoal(Literal.of("handle_alert", "payment"));

        engine.step(agent);
    }

    @Test
    void shouldSendMessageViaBuiltin() {
        var engine = new BdiReasoningEngine();
        var agent = simpleAgent("sender");
        var ctx = new StubContext();
        engine.start(agent, ctx);

        String plans = "+handle_alert(payment) : true <- .send(sm, cfp, \"{\\\"service\\\":\\\"payment\\\"}\").";
        engine.library(agent).addAll(AslParser.parse(plans));
        engine.beliefs(agent).add(Literal.of("handle_alert", "payment"));
        engine.goals(agent).addAchievementGoal(Literal.of("handle_alert", "payment"));

        engine.step(agent);
        assertThat(ctx.sentMessages).hasSize(1);
        assertThat(ctx.sentMessages.get(0).performative()).isEqualTo(ACLMessage.Performative.CFP);
    }

    private Agent simpleAgent(String name) {
        AgentId id = AgentId.of(name);
        return new Agent() {
            @Override public AgentId agentId() { return id; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
    }

    static class StubContext implements AgentContext {
        final java.util.List<ACLMessage> sentMessages = new java.util.ArrayList<>();

        @Override public AgentId self() { return AgentId.of("test"); }
        @Override public AgentRegistry registry() { return null; }
        @Override public ServiceDirectory services() {
            return new ServiceDirectory() {
                @Override public void register(ServiceDescription s) {}
                @Override public void deregister(AgentId p, String t) {}
                @Override public List<ServiceDescription> search(String t) { return List.of(); }
                @Override public List<ServiceDescription> search(String t, Predicate<Map<String, String>> c) { return List.of(); }
                @Override public SubscriptionToken subscribe(String t, Consumer<List<ServiceDescription>> cb) { return null; }
                @Override public void unsubscribe(SubscriptionToken t) {}
            };
        }
        @Override public void send(ACLMessage msg) { sentMessages.add(msg); }
        @Override public ScheduledExecutorService scheduler() { return Executors.newSingleThreadScheduledExecutor(); }
    }
}
