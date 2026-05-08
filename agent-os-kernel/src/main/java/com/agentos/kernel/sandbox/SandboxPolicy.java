package com.agentos.kernel.sandbox;

import java.util.Set;

/**
 * Defines what a sandboxed agent is allowed to do.
 *
 * Configure via YAML:
 *   sandbox:
 *     allowed-packages: [java.lang, java.util, com.agentos.kernel.messaging]
 *     blocked-packages: [java.lang.reflect, java.lang.invoke, sun.misc]
 *     allow-file-io: false
 *     allow-network: true
 *     allow-system-exit: false
 *     allow-runtime-exec: false
 *     max-cpu-time-seconds: 30
 *     max-memory-bytes: 67108864
 */
public record SandboxPolicy(
    Set<String> allowedPackages,
    Set<String> blockedPackages,
    boolean allowFileIo,
    boolean allowNetwork,
    boolean allowSystemExit,
    boolean allowRuntimeExec,
    long maxCpuTimeSeconds,
    long maxMemoryBytes
) {
    public static final Set<String> DEFAULT_ALLOWED = Set.of(
        "java.lang", "java.util", "java.math", "java.time",
        "java.util.concurrent", "java.util.stream", "java.util.function",
        "com.agentos.kernel", "com.agentos.kernel.messaging",
        "org.slf4j"
    );

    public static final Set<String> DEFAULT_BLOCKED = Set.of(
        "java.lang.reflect", "java.lang.invoke", "java.lang.ProcessBuilder",
        "sun.misc", "sun.reflect", "jdk.internal"
    );

    public SandboxPolicy {
        if (allowedPackages == null || allowedPackages.isEmpty())
            allowedPackages = DEFAULT_ALLOWED;
        if (blockedPackages == null || blockedPackages.isEmpty())
            blockedPackages = DEFAULT_BLOCKED;
        if (maxCpuTimeSeconds <= 0) maxCpuTimeSeconds = 30;
        if (maxMemoryBytes <= 0) maxMemoryBytes = 64 * 1024 * 1024; // 64 MB
    }

    public static SandboxPolicy defaults() {
        return new SandboxPolicy(null, null, false, true, false, false, 0, 0);
    }

    public static SandboxPolicy permissive() {
        return new SandboxPolicy(null, null, true, true, true, true, 300, 256 * 1024 * 1024L);
    }

    public static SandboxPolicy strict() {
        return new SandboxPolicy(
            Set.of("java.lang", "java.util", "com.agentos.kernel.messaging"),
            Set.of("java.lang.reflect", "java.lang.invoke", "java.io", "java.net",
                "java.nio", "sun.misc", "jdk.internal"),
            false, false, false, false, 10, 16 * 1024 * 1024L
        );
    }

    /**
     * Check if a given class is allowed under this policy.
     */
    public boolean isClassAllowed(String className) {
        // Blocked packages take priority
        for (String blocked : blockedPackages) {
            if (className.startsWith(blocked)) return false;
        }
        // Check allowed packages
        for (String allowed : allowedPackages) {
            if (className.startsWith(allowed)) return true;
        }
        // Default deny
        return false;
    }
}
