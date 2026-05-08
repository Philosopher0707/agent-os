package com.agentos.kernel.reasoning;

import com.agentos.kernel.Agent;
import com.agentos.kernel.AgentContext;
import com.agentos.kernel.messaging.ACLMessage;

public interface ReasoningEngine {
    String name();
    boolean supports(Agent agent);
    void install(Agent agent);
    void start(Agent agent, AgentContext ctx);
    void onMessage(Agent agent, ACLMessage msg);
    void step(Agent agent);
    void stop(Agent agent);
}
