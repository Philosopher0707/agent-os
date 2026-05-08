package com.agentos.kernel;

/**
 * Health status for a single agent.
 */
public record AgentHealth(
    String name,
    AgentLifecycle state,
    int consecutiveFailures,
    boolean sandboxed,
    long sandboxViolations,
    boolean hasError,
    java.time.Instant checkedAt
) {}
