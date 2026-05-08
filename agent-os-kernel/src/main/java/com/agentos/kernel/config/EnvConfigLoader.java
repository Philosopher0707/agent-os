package com.agentos.kernel.config;

import com.agentos.kernel.AgentOsConfig;
import com.agentos.kernel.ConfigLoader;
import java.time.Duration;

public class EnvConfigLoader implements ConfigLoader {

    @Override
    public AgentOsConfig load() {
        var defaults = AgentOsConfig.defaults();
        return new AgentOsConfig(
            durationEnv("AGENTOS_TICK_INTERVAL", defaults.tickInterval()),
            durationEnv("AGENTOS_STEP_TIMEOUT", defaults.stepTimeout()),
            intEnv("AGENTOS_MAILBOX_CAPACITY", defaults.mailboxCapacity()),
            intEnv("AGENTOS_MAX_RETRIES", defaults.maxRetries()),
            durationEnv("AGENTOS_GRACEFUL_SHUTDOWN", defaults.gracefulShutdown()),
            intEnv("AGENTOS_CONSECUTIVE_FAILURE_LIMIT", defaults.consecutiveFailureLimit()),
            durationEnv("AGENTOS_STALE_ENTRY_TTL", defaults.staleEntryTtl()),
            durationEnv("AGENTOS_ROUTING_CACHE_TTL", defaults.routingCacheTtl()),
            intEnv("AGENTOS_MANAGEMENT_PORT", defaults.managementPort()),
            intEnv("AGENTOS_DLQ_MAX_ENTRIES", defaults.dlqMaxEntries()),
            strEnv("AGENTOS_AUTH_SECRET", defaults.authSecret()),
            longEnv("AGENTOS_AUTH_TOKEN_TTL", defaults.authTokenTtlSeconds()),
            boolEnv("AGENTOS_SANDBOX_ENABLED", defaults.sandboxEnabled()),
            strEnv("AGENTOS_SANDBOX_POLICY", defaults.sandboxPolicy())
        );
    }

    @Override
    public int priority() { return 90; }

    private static Duration durationEnv(String key, Duration fallback) {
        String val = System.getenv(key);
        return val != null ? PropertiesConfigLoader.parseDuration(val) : fallback;
    }

    private static int intEnv(String key, int fallback) {
        String val = System.getenv(key);
        return val != null ? Integer.parseInt(val) : fallback;
    }

    private static long longEnv(String key, long fallback) {
        String val = System.getenv(key);
        return val != null ? Long.parseLong(val) : fallback;
    }

    private static String strEnv(String key, String fallback) {
        String val = System.getenv(key);
        return val != null ? val : fallback;
    }

    private static boolean boolEnv(String key, boolean fallback) {
        String val = System.getenv(key);
        return val != null ? "true".equalsIgnoreCase(val) : fallback;
    }
}
