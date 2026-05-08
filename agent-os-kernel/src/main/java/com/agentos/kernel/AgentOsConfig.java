package com.agentos.kernel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public record AgentOsConfig(
    Duration tickInterval,
    Duration stepTimeout,
    int mailboxCapacity,
    int maxRetries,
    Duration gracefulShutdown,
    int consecutiveFailureLimit,
    Duration staleEntryTtl,
    Duration routingCacheTtl,
    int managementPort,
    int dlqMaxEntries,
    String authSecret,
    long authTokenTtlSeconds,
    boolean sandboxEnabled,
    String sandboxPolicy // "default", "permissive", "strict"
) {
    public AgentOsConfig {
        if (tickInterval == null) tickInterval = Duration.ofMillis(100);
        if (stepTimeout == null) stepTimeout = Duration.ofSeconds(30);
        if (mailboxCapacity <= 0) mailboxCapacity = 10_000;
        if (maxRetries <= 0) maxRetries = 3;
        if (gracefulShutdown == null) gracefulShutdown = Duration.ofSeconds(5);
        if (consecutiveFailureLimit <= 0) consecutiveFailureLimit = 5;
        if (staleEntryTtl == null) staleEntryTtl = Duration.ofSeconds(60);
        if (routingCacheTtl == null) routingCacheTtl = Duration.ofSeconds(30);
        if (managementPort <= 0) managementPort = 9091;
        if (dlqMaxEntries <= 0) dlqMaxEntries = 10_000;
        if (authTokenTtlSeconds <= 0) authTokenTtlSeconds = 3600;
        if (sandboxPolicy == null || sandboxPolicy.isBlank()) sandboxPolicy = "default";
    }

    public static AgentOsConfig defaults() {
        return new AgentOsConfig(null, null, 0, 0, null, 0, null, null, 0, 0, null, 0, false, null);
    }

    /** Validate config and return warnings for suspicious values. */
    public List<String> validate() {
        List<String> warnings = new ArrayList<>();
        if (tickInterval.toMillis() < 10) warnings.add("tickInterval < 10ms may cause excessive CPU usage");
        if (mailboxCapacity < 100) warnings.add("mailboxCapacity < 100 may cause message loss under load");
        if (maxRetries < 0) warnings.add("maxRetries is negative; deliveries will never retry");
        if (consecutiveFailureLimit < 1) warnings.add("consecutiveFailureLimit < 1 means any failure kills agents");
        if (managementPort <= 0 || managementPort > 65535) warnings.add("managementPort out of valid range");
        if (authTokenTtlSeconds < 60) warnings.add("authTokenTtlSeconds < 60 may cause frequent re-auth");
        return warnings;
    }

    // Needed for the import in KernelManagement:
    // This record intentionally does NOT import java.util.* to avoid clutter.
    // Callers should use AgentOsConfig.validate().
}
