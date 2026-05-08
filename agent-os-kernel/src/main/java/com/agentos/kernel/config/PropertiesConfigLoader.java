package com.agentos.kernel.config;

import com.agentos.kernel.AgentOsConfig;
import com.agentos.kernel.ConfigLoader;
import java.time.Duration;

public class PropertiesConfigLoader implements ConfigLoader {

    @Override
    public AgentOsConfig load() {
        var defaults = AgentOsConfig.defaults();
        return new AgentOsConfig(
            durationProp("agentos.tick.interval", defaults.tickInterval()),
            durationProp("agentos.step.timeout", defaults.stepTimeout()),
            intProp("agentos.mailbox.capacity", defaults.mailboxCapacity()),
            intProp("agentos.max.retries", defaults.maxRetries()),
            durationProp("agentos.graceful.shutdown", defaults.gracefulShutdown()),
            intProp("agentos.consecutive.failure.limit", defaults.consecutiveFailureLimit()),
            durationProp("agentos.stale.entry.ttl", defaults.staleEntryTtl()),
            durationProp("agentos.routing.cache.ttl", defaults.routingCacheTtl()),
            intProp("agentos.management.port", defaults.managementPort()),
            intProp("agentos.dlq.max.entries", defaults.dlqMaxEntries()),
            strProp("agentos.auth.secret", defaults.authSecret()),
            longProp("agentos.auth.token.ttl", defaults.authTokenTtlSeconds()),
            boolProp("agentos.sandbox.enabled", defaults.sandboxEnabled()),
            strProp("agentos.sandbox.policy", defaults.sandboxPolicy())
        );
    }

    @Override
    public int priority() { return 100; }

    private static Duration durationProp(String key, Duration fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            return parseDuration(val);
        }
        return fallback;
    }

    private static int intProp(String key, int fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            return Integer.parseInt(val);
        }
        return fallback;
    }

    private static long longProp(String key, long fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            return Long.parseLong(val);
        }
        return fallback;
    }

    private static String strProp(String key, String fallback) {
        String val = System.getProperty(key);
        return val != null && !val.isBlank() ? val : fallback;
    }

    private static boolean boolProp(String key, boolean fallback) {
        String val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            return "true".equalsIgnoreCase(val);
        }
        return fallback;
    }

    public static Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.replace("ms", "")));
        if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.replace("s", "")));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.replace("m", "")));
        return Duration.ofMillis(Long.parseLong(s));
    }
}
