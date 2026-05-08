package com.agentos.config.yaml;

import com.agentos.kernel.AgentOsConfig;
import com.agentos.kernel.ConfigLoader;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class YamlConfigLoader implements ConfigLoader {

    private static final String DEFAULT_PATH = "agent-os.yaml";
    private static final String ENV_PATH_KEY = "AGENTOS_CONFIG_PATH";

    @Override
    public AgentOsConfig load() {
        var defaults = AgentOsConfig.defaults();
        Map<String, Object> yaml = loadYaml();
        if (yaml == null) return null;

        return new AgentOsConfig(
            duration(yaml, "tickInterval", defaults.tickInterval()),
            duration(yaml, "stepTimeout", defaults.stepTimeout()),
            intVal(yaml, "mailboxCapacity", defaults.mailboxCapacity()),
            intVal(yaml, "maxRetries", defaults.maxRetries()),
            duration(yaml, "gracefulShutdown", defaults.gracefulShutdown()),
            intVal(yaml, "consecutiveFailureLimit", defaults.consecutiveFailureLimit()),
            duration(yaml, "staleEntryTtl", defaults.staleEntryTtl()),
            duration(yaml, "routingCacheTtl", defaults.routingCacheTtl()),
            intVal(yaml, "managementPort", defaults.managementPort()),
            intVal(yaml, "dlqMaxEntries", defaults.dlqMaxEntries()),
            strVal(yaml, "authSecret", defaults.authSecret()),
            longVal(yaml, "authTokenTtlSeconds", defaults.authTokenTtlSeconds()),
            boolVal(yaml, "sandboxEnabled", defaults.sandboxEnabled()),
            strVal(yaml, "sandboxPolicy", defaults.sandboxPolicy())
        );
    }

    @Override
    public int priority() { return 200; } // Higher than properties/env

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml() {
        String configPath = System.getenv().getOrDefault(ENV_PATH_KEY, DEFAULT_PATH);
        Path path = Path.of(configPath);

        // Try filesystem first, then classpath
        try {
            if (Files.exists(path)) {
                Yaml yaml = new Yaml();
                try (InputStream is = Files.newInputStream(path)) {
                    return yaml.load(is);
                }
            }
        } catch (Exception e) {
            // Fall through to classpath
        }

        // Try classpath
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(configPath)) {
            if (is != null) {
                Yaml yaml = new Yaml();
                return yaml.load(is);
            }
        } catch (Exception e) {
            // No config file found
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Duration duration(Map<String, Object> map, String key, Duration fallback) {
        Object val = map.get(key);
        if (val == null) {
            // Try kebab-case
            val = map.get(toKebab(key));
        }
        if (val instanceof String s) {
            return parseDuration(s);
        }
        if (val instanceof Number n) {
            return Duration.ofMillis(n.longValue());
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static int intVal(Map<String, Object> map, String key, int fallback) {
        Object val = map.get(key);
        if (val == null) {
            val = map.get(toKebab(key));
        }
        if (val instanceof Number n) {
            return n.intValue();
        }
        if (val instanceof String s) {
            return Integer.parseInt(s);
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static long longVal(Map<String, Object> map, String key, long fallback) {
        Object val = map.get(key);
        if (val == null) {
            val = map.get(toKebab(key));
        }
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s) {
            return Long.parseLong(s);
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static String strVal(Map<String, Object> map, String key, String fallback) {
        Object val = map.get(key);
        if (val == null) {
            val = map.get(toKebab(key));
        }
        if (val instanceof String s) {
            return s;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static boolean boolVal(Map<String, Object> map, String key, boolean fallback) {
        Object val = map.get(key);
        if (val == null) {
            val = map.get(toKebab(key));
        }
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return "true".equalsIgnoreCase(s);
        }
        return fallback;
    }

    private static String toKebab(String camelCase) {
        return camelCase.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    static Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.replace("ms", "")));
        if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.replace("s", "")));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.replace("m", "")));
        if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.replace("h", "")));
        return Duration.ofMillis(Long.parseLong(s));
    }
}
