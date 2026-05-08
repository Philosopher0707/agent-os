package com.agentos.kernel;

import com.agentos.kernel.directory.AgentRegistry;
import com.agentos.kernel.directory.ServiceDirectory;
import com.agentos.kernel.messaging.ACLMessage;

import java.util.concurrent.ScheduledExecutorService;

public interface AgentContext {
    AgentId self();
    AgentRegistry registry();
    ServiceDirectory services();
    void send(ACLMessage msg);
    ScheduledExecutorService scheduler();
}
