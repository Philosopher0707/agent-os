package com.agentos.kernel.directory;

import com.agentos.kernel.*;

public interface AgentRegistry {
    AgentId register(Agent agent, String containerId) throws AgentExistsException;
    void unregister(AgentId id);
    java.util.Optional<AgentInfo> lookup(String name);
    java.util.Optional<AgentInfo> lookup(java.util.UUID id);
    java.util.List<AgentInfo> listByContainer(String containerId);
    AgentLifecycle getState(AgentId id);
    void setState(AgentId id, AgentLifecycle state);
}
