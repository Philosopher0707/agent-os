package com.agentos.reasoning.reactive;

import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.reasoning.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;

public final class ReactiveReasoningEngine implements ReasoningEngine {
    private static final Logger log = LoggerFactory.getLogger(ReactiveReasoningEngine.class);
    private final Map<Agent, List<Behavior>> agentBehaviors = new ConcurrentHashMap<>();
    private final Map<Agent, AgentContext> agentContexts = new ConcurrentHashMap<>();

    @Override public String name() { return "reactive"; }
    @Override public boolean supports(Agent agent) { return true; }

    @Override public void install(Agent agent) {
        agentBehaviors.putIfAbsent(agent, new CopyOnWriteArrayList<>());
    }

    public void addBehavior(Agent agent, Behavior behavior) {
        agentBehaviors.computeIfAbsent(agent, k -> new CopyOnWriteArrayList<>()).add(behavior);
    }

    @Override public void start(Agent agent, AgentContext ctx) {
        install(agent);
        agentContexts.put(agent, ctx);
    }

    @Override
    public void onMessage(Agent agent, ACLMessage msg) {
        List<Behavior> behaviors = agentBehaviors.get(agent);
        if (behaviors == null) { agent.onMessage(msg); return; }
        for (Behavior b : behaviors) {
            if (b.matches(msg)) {
                AgentContext ctx = agentContexts.get(agent);
                b.handle(msg, ctx);
                return;
            }
        }
        agent.onMessage(msg);
    }

    @Override
    public void step(Agent agent) {
        List<Behavior> behaviors = agentBehaviors.get(agent);
        if (behaviors != null) {
            AgentContext ctx = agentContexts.get(agent);
            for (Behavior b : behaviors) {
                if (ctx != null) b.onTick(ctx);
            }
        }
    }

    @Override public void stop(Agent agent) {
        agentBehaviors.remove(agent);
        agentContexts.remove(agent);
    }
}
