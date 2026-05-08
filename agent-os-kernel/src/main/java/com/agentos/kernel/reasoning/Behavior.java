package com.agentos.kernel.reasoning;

import com.agentos.kernel.AgentContext;
import com.agentos.kernel.messaging.ACLMessage;
import java.util.concurrent.CompletionStage;

public interface Behavior {
    boolean matches(ACLMessage msg);
    CompletionStage<Void> handle(ACLMessage msg, AgentContext ctx);
    default void onTick(AgentContext ctx) {}
}
