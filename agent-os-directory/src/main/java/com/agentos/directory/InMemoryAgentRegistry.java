package com.agentos.directory;

import com.agentos.kernel.*;
import com.agentos.kernel.directory.AgentRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class InMemoryAgentRegistry implements AgentRegistry {

    private final Map<AgentId, AgentInfo> agents = new ConcurrentHashMap<>();
    private final Map<String, AgentId> nameIndex = new ConcurrentHashMap<>();

    @Override
    public AgentId register(Agent agent, String containerId) throws AgentExistsException {
        String name = agent.agentId().name();
        if (nameIndex.containsKey(name)) {
            throw new AgentExistsException(name);
        }
        AgentId id = agent.agentId();
        AgentInfo info = new AgentInfo(id, containerId, AgentLifecycle.INITIATED, Instant.now());
        agents.put(id, info);
        nameIndex.put(name, id);
        return id;
    }

    @Override
    public void unregister(AgentId id) {
        agents.remove(id);
        nameIndex.remove(id.name());
    }

    @Override
    public Optional<AgentInfo> lookup(String name) {
        AgentId id = nameIndex.get(name);
        return id != null ? Optional.ofNullable(agents.get(id)) : Optional.empty();
    }

    @Override
    public Optional<AgentInfo> lookup(UUID id) {
        return agents.entrySet().stream()
            .filter(e -> e.getKey().id().equals(id))
            .map(Map.Entry::getValue)
            .findFirst();
    }

    @Override
    public List<AgentInfo> listByContainer(String containerId) {
        return agents.values().stream()
            .filter(i -> i.containerId().equals(containerId))
            .collect(Collectors.toList());
    }

    @Override
    public AgentLifecycle getState(AgentId id) {
        AgentInfo info = agents.get(id);
        return info != null ? info.state() : AgentLifecycle.TERMINATED;
    }

    @Override
    public void setState(AgentId id, AgentLifecycle state) {
        AgentInfo current = agents.get(id);
        if (current != null) {
            agents.put(id, new AgentInfo(id, current.containerId(), state, current.registeredAt()));
        }
    }
}
