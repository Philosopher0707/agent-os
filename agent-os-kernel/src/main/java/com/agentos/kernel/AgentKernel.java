package com.agentos.kernel;

import com.agentos.kernel.directory.AgentRegistry;
import com.agentos.kernel.directory.ServiceDirectory;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.messaging.MessageTransport;
import com.agentos.kernel.messaging.MessagingProtocol;
import com.agentos.kernel.persistence.MessageStore;
import com.agentos.kernel.persistence.AgentStateStore;
import com.agentos.kernel.reasoning.ReasoningEngine;
import java.util.concurrent.CompletableFuture;

public interface AgentKernel extends AutoCloseable {
    AgentId register(Agent agent);
    void unregister(AgentId id);
    void transition(AgentId id, AgentLifecycle state);
    AgentLifecycle stateOf(AgentId id);
    void send(ACLMessage msg);
    AgentContext contextOf(AgentId id);
    AgentKernelHealth health();

    /**
     * Migrate a mobile agent to another container.
     * The agent is checkpointed, suspended, sent to the target, and restored there.
     */
    CompletableFuture<MigrationResult> migrate(AgentId id, String targetContainer);

    void bind(MessageTransport transport);
    void bind(AgentRegistry registry);
    void bind(ServiceDirectory services);
    void bind(ReasoningEngine engine);
    void bind(MessageStore store);
    void bind(AgentStateStore store);
    void bind(MessagingProtocol protocol);
    void start();

    @Override
    void close();
}
