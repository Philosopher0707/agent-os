package com.agentos.kernel;

public record AgentKernelHealth(
    String containerId,
    int activeAgents,
    int suspendedAgents,
    int terminatedAgents,
    long messagesRouted,
    long messagesFailed,
    boolean directoryAvailable,
    boolean transportAvailable,
    int sandboxedAgents,
    long sandboxViolations
) {}
