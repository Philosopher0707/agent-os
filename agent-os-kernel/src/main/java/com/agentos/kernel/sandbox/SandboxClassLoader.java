package com.agentos.kernel.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClassLoader that enforces a SandboxPolicy by denying access to blocked packages
 * and classes not in the allowed set.
 *
 * Delegates to the parent ClassLoader for allowed classes; throws ClassNotFoundException
 * for blocked or disallowed classes.
 */
public final class SandboxClassLoader extends ClassLoader {
    private static final Logger log = LoggerFactory.getLogger(SandboxClassLoader.class);

    private final SandboxPolicy policy;
    private final String agentId;
    private final Set<String> loadedClasses = ConcurrentHashMap.newKeySet();

    public SandboxClassLoader(ClassLoader parent, SandboxPolicy policy, String agentId) {
        super(parent);
        this.policy = policy;
        this.agentId = agentId;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        // Always allow core JVM classes (needed for basic operation)
        if (isJvmInternal(name)) {
            return super.loadClass(name);
        }

        // Check policy
        if (!policy.isClassAllowed(name)) {
            log.warn("Sandbox[{}]: blocked class load attempt: {}", agentId, name);
            throw new ClassNotFoundException("Blocked by sandbox policy: " + name);
        }

        Class<?> clazz = super.loadClass(name);
        loadedClasses.add(name);
        return clazz;
    }

    /**
     * JVM-internal classes that are always allowed (needed for basic operation).
     */
    private boolean isJvmInternal(String name) {
        // Always allow core java.lang types (needed for basic operation)
        if (name.startsWith("java.lang.")) {
            // Block dangerous java.lang sub-packages
            if (name.startsWith("java.lang.reflect") || name.startsWith("java.lang.invoke")
                || name.startsWith("java.lang.ProcessBuilder")) {
                return false;
            }
            return true;
        }
        // Allow core JVM constructs
        return name.startsWith("java.lang.Object") ||
               name.startsWith("java.lang.Class") ||
               name.startsWith("java.lang.String") ||
               name.startsWith("java.lang.Integer") ||
               name.startsWith("java.lang.Long") ||
               name.startsWith("java.lang.Double") ||
               name.startsWith("java.lang.Boolean") ||
               name.startsWith("java.lang.Number") ||
               name.startsWith("java.lang.Enum") ||
               name.startsWith("java.lang.Record") ||
               name.startsWith("java.lang.Iterable") ||
               name.startsWith("java.lang.CharSequence") ||
               name.startsWith("java.lang.Comparable") ||
               name.startsWith("java.lang.AutoCloseable") ||
               name.startsWith("java.lang.Throwable") ||
               name.startsWith("java.lang.Exception") ||
               name.startsWith("java.lang.RuntimeException") ||
               name.startsWith("java.lang.Error") ||
               name.startsWith("java.lang.Void") ||
               name.startsWith("java.lang.System") ||
               name.startsWith("java.lang.Math") ||
               name.startsWith("java.lang.Override") ||
               name.startsWith("java.lang.Deprecated") ||
               name.startsWith("java.lang.SuppressWarnings") ||
               name.startsWith("java.lang.SafeVarargs") ||
               name.startsWith("java.lang.FunctionalInterface") ||
               name.startsWith("[L") ||
               name.startsWith("java.lang.invoke.Lambda") ||
               name.startsWith("java.lang.invoke.MethodHandle") ||
               name.startsWith("java.lang.invoke.TypeDescriptor") ||
               name.startsWith("java.util.");
    }

    public Set<String> loadedClasses() {
        return Set.copyOf(loadedClasses);
    }

    public String agentId() {
        return agentId;
    }
}
