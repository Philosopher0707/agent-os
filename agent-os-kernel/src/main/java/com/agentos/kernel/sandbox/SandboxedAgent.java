package com.agentos.kernel.sandbox;

import com.agentos.kernel.Agent;
import com.agentos.kernel.AgentContext;
import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps an Agent to enforce sandbox restrictions.
 *
 * Protection layers:
 *   1. SandboxClassLoader — restricts which classes the agent can load
 *   2. Thread interruption — kills agent if it exceeds CPU time limit
 *   3. Exception catching — prevents agent exceptions from crashing the kernel
 *   4. Violation tracking — counts and reports policy violations
 *
 * Usage:
 *   Agent rawAgent = new MyAgent();
 *   SandboxedAgent sandboxed = new SandboxedAgent(rawAgent, SandboxPolicy.strict());
 *   kernel.register(sandboxed);
 */
public final class SandboxedAgent implements Agent {
    private static final Logger log = LoggerFactory.getLogger(SandboxedAgent.class);

    private final Agent delegate;
    private final SandboxPolicy policy;
    private final SandboxClassLoader classLoader;
    private final AtomicLong violationCount = new AtomicLong(0);
    private final AtomicLong cpuTimeUsedMs = new AtomicLong(0);
    private volatile Instant lastViolationAt;
    private volatile String lastViolationReason;
    private volatile Throwable lastError;
    private AgentContext context;

    private static ExecutorService SANDBOX_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sandbox-worker");
        t.setDaemon(true);
        return t;
    });

    public SandboxedAgent(Agent delegate, SandboxPolicy policy) {
        this.delegate = delegate;
        this.policy = policy;
        this.classLoader = new SandboxClassLoader(
            delegate.getClass().getClassLoader(),
            policy,
            delegate.agentId().name()
        );
    }

    public SandboxedAgent(Agent delegate) {
        this(delegate, SandboxPolicy.defaults());
    }

    @Override
    public AgentId agentId() {
        return delegate.agentId();
    }

    @Override
    public boolean isEventDriven() {
        return delegate.isEventDriven();
    }

    @Override
    public void init(AgentContext ctx) {
        this.context = ctx;
        runSandboxed("init", () -> delegate.init(ctx));
    }

    @Override
    public void step() {
        runSandboxed("step", () -> delegate.step());
    }

    @Override
    public void onMessage(ACLMessage msg) {
        runSandboxed("onMessage", () -> delegate.onMessage(msg));
    }

    @Override
    public void suspend() {
        runSandboxed("suspend", () -> delegate.suspend());
    }

    @Override
    public void resume() {
        runSandboxed("resume", () -> delegate.resume());
    }

    @Override
    public void shutdown() {
        runSandboxed("shutdown", () -> delegate.shutdown());
    }

    /**
     * Execute a sandboxed operation with timeout and violation detection.
     */
    private void runSandboxed(String operation, Runnable task) {
        Instant start = Instant.now();
        lastError = null;  // clear previous error on new operation

        try {
            // Use shared thread pool — each call gets its own thread for isolation but threads are reused
            Future<?> future = SANDBOX_EXECUTOR.submit(() -> {
                Thread.currentThread().setContextClassLoader(classLoader);
                task.run();
            });

            try {
                future.get(policy.maxCpuTimeSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                recordViolation("CPU time limit exceeded during " + operation);
                log.error("Sandbox[{}]: {} timed out after {}s",
                    agentId().name(), operation, policy.maxCpuTimeSeconds());
                throw new SandboxViolationException(
                    "CPU time limit exceeded: " + policy.maxCpuTimeSeconds() + "s");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                lastError = cause;
                if (cause instanceof SandboxViolationException) throw (SandboxViolationException) cause;
                recordViolation("Exception during " + operation + ": " + cause.getMessage());
                log.warn("Sandbox[{}]: {} threw: {}", agentId().name(), operation, cause.getMessage());
            }

        } catch (SandboxViolationException e) {
            lastError = e;
            recordViolation(e.getMessage());
            log.error("Sandbox[{}]: violation in {}: {}", agentId().name(), operation, e.getMessage());
        } catch (Exception e) {
            lastError = e;
            recordViolation("Unexpected error in " + operation + ": " + e.getMessage());
            log.error("Sandbox[{}]: unexpected error in {}: {}", agentId().name(), operation, e.getMessage());
        }

        cpuTimeUsedMs.addAndGet(Duration.between(start, Instant.now()).toMillis());
    }

    private void recordViolation(String reason) {
        violationCount.incrementAndGet();
        lastViolationAt = Instant.now();
        lastViolationReason = reason;
    }

    // ──── Inspection ────

    public Agent delegate() { return delegate; }
    public SandboxPolicy policy() { return policy; }
    public long violationCount() { return violationCount.get(); }
    public long cpuTimeUsedMs() { return cpuTimeUsedMs.get(); }
    public Instant lastViolationAt() { return lastViolationAt; }
    public String lastViolationReason() { return lastViolationReason; }
    public Optional<Throwable> lastError() { return Optional.ofNullable(lastError); }
    public boolean hasFailed() { return lastError != null; }
    public Set<String> loadedClasses() { return classLoader.loadedClasses(); }

    /** Shut down the shared sandbox executor. Call during JVM shutdown. */
    public static void shutdownExecutor() { SANDBOX_EXECUTOR.shutdown(); }

    /** Reset the shared executor (for test cleanup between runs). */
    public static void resetExecutor() {
        SANDBOX_EXECUTOR.shutdown();
        try { SANDBOX_EXECUTOR.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        SANDBOX_EXECUTOR = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sandbox-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Exception thrown when a sandbox violation is detected.
     */
    public static final class SandboxViolationException extends RuntimeException {
        public SandboxViolationException(String message) {
            super(message);
        }
    }
}
