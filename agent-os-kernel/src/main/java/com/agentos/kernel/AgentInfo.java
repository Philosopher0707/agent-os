package com.agentos.kernel;

import java.time.Instant;

public record AgentInfo(
    AgentId agentId,
    String containerId,
    AgentLifecycle state,
    Instant registeredAt
) {}
