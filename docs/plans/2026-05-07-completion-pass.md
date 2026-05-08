# Agent OS Completion Pass — Implementation Plan

> **For Hermes:** Execute task-by-task, 2-stage review (spec compliance + code quality) per task. Fresh subagent per task group.

**Goal:** Fix the ~30 critical/major/moderate gaps identified in the deep audit, bringing agent-os from prototype to production-grade.

**Architecture:** Five sequential phases: (1) runtime crash fixes, (2) logic gap closures, (3) missing endpoints, (4) sandbox completion, (5) hardening. Each phase builds on the previous.

**Tech Stack:** Java 21, Gradle, gRPC, Jetty 11, Kafka, Postgres (HikariCP), LangChain4j

---

## Phase 1: Runtime Crash Fixes (🔴 Critical)

### Task 1.1: Fix token-auth dead code — enforce secret on /token endpoint

**Objective:** Require the shared secret to issue tokens. The `instanceof` no-op block must become a real check.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java:156-162`

**Step 1: Read current code**

Read lines 148-165 of KernelManagement.java for context.

**Step 2: Replace the no-op auth block**

Replace:
```java
if (tokenAuth instanceof com.agentos.kernel.auth.TokenAuth ta) {
    // Secret check is implicit...
}
```
With:
```java
// Validate the shared secret matches
if (secret == null || !MessageDigest.isEqual(
    secret.getBytes(StandardCharsets.UTF_8),
    tokenAuth.sharedSecret().getBytes(StandardCharsets.UTF_8))) {
    sendJson(exchange, 403, "{\"error\":\"invalid secret\"}");
    return;
}
```

**Step 3: Add `sharedSecret()` accessor to TokenAuth**

Files: `agent-os-kernel/src/main/java/com/agentos/kernel/auth/TokenAuth.java`

Add:
```java
public String sharedSecret() { return sharedSecret; }
```

**Step 4: Add import for MessageDigest and StandardCharsets in KernelManagement**

Already imported StandardCharsets. Add:
```java
import java.security.MessageDigest;
```

**Step 5: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java
git add agent-os-kernel/src/main/java/com/agentos/kernel/auth/TokenAuth.java
git commit -m "fix: enforce shared secret on /token endpoint"
```

---

### Task 1.2: Fix /inject-fault to actually inject faults

**Objective:** The `/inject-fault` endpoint reads params and returns success without doing anything. Wire it to the kernel.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java:168-178`

**Step 1: Change KernelManagement constructor to accept a fault-injection callback**

Add parameter: `java.util.function.BiConsumer<String, String> faultInjector`

**Step 2: Wire the fault injection handler**

Replace the stub body with:
```java
if (faultInjector != null) {
    faultInjector.accept(agent, type);
    sendJson(exchange, 200, "{\"injected\":true,\"agent\":\"" + agent
        + "\",\"type\":\"" + type + "\"}");
} else {
    sendJson(exchange, 501, "{\"error\":\"fault injection not configured\"}");
}
```

**Step 3: Add a default fault injector in DefaultAgentKernel**

Create a method that translates `agentId + faultType` into lifecycle transitions (crash→TRANSIENT, hang→SUSPENDED, etc.) and pass it when constructing KernelManagement.

**Step 4: Update DefaultAgentKernel to pass the injector**

In `DefaultAgentKernel.start()`, construct KernelManagement with the injector:
```java
management = new KernelManagement(mgmtPort, this::health, deadLetterQueue, tokenAuth,
    (agentName, faultType) -> {
        // Look up agent by name or ID
        sessions.keySet().stream()
            .filter(id -> id.name().equals(agentName))
            .findFirst()
            .ifPresent(id -> {
                switch (faultType) {
                    case "crash" -> transition(id, AgentLifecycle.TRANSIENT);
                    case "hang" -> transition(id, AgentLifecycle.SUSPENDED);
                    case "slow" -> log.info("Injected slow fault into {}", agentName);
                    case "memory" -> log.info("Injected memory fault into {}", agentName);
                }
            });
    });
```

**Step 5: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java
git commit -m "fix: wire /inject-fault to kernel lifecycle transitions"
```

---

### Task 1.3: Fix DLQ replay to actually re-deliver messages

**Objective:** The `replayAll` and `replayConversation` accept a `Predicate` that always returns `true` without doing actual delivery. Wire in the kernel's send method.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java:278-289`
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DeadLetterQueue.java` (add a method overload)

**Step 1: Add a Consumer-based replay method to DeadLetterQueue**

Add to DeadLetterQueue:
```java
public int replayAllWithSender(java.util.function.Consumer<ACLMessage> sender) {
    int replayed = 0;
    List<DeadLetterEntry> toRemove = new ArrayList<>();
    for (DeadLetterEntry entry : entries) {
        try {
            sender.accept(entry.message);
            toRemove.add(entry);
            replayed++;
        } catch (Exception e) {
            log.warn("DLQ replay send failed for convId={}: {}", entry.message.conversationId(), e.getMessage());
        }
    }
    entries.removeAll(toRemove);
    totalReplayed.addAndGet(replayed);
    log.info("DLQ: replayed {} messages, {} remaining", replayed, entries.size());
    return replayed;
}
```

**Step 2: Wire KernelManagement to pass the kernel send callback**

Update KernelManagement constructor to accept `Consumer<ACLMessage> messageSender`, then use it in the `/dlq/replay` handler:
```java
replayed = deadLetterQueue.replayAllWithSender(messageSender);
```

**Step 3: Update DefaultAgentKernel to pass `this::send`**

**Step 4: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/DeadLetterQueue.java
git add agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java
git commit -m "fix: wire DLQ replay to actual message re-delivery"
```

---

### Task 1.4: Fix ReactiveReasoningEngine passing null context to behaviors

**Objective:** `Behavior.handle(msg, null)` and `onTick(null)` will NPE on any behavior that uses context. Pass the actual context.

**Files:**
- Modify: `agent-os-reasoning-reactive/src/main/java/com/agentos/reasoning/reactive/ReactiveReasoningEngine.java:30-43`

**Step 1: Add context tracking map**

Add instance field:
```java
private final Map<Agent, AgentContext> agentContexts = new ConcurrentHashMap<>();
```

**Step 2: Store context on start**

```java
@Override
public void start(Agent agent, AgentContext ctx) {
    install(agent);
    agentContexts.put(agent, ctx);
}
```

**Step 3: Pass context to behavior calls**

Replace `b.handle(msg, null)` with:
```java
AgentContext ctx = agentContexts.get(agent);
b.handle(msg, ctx);
```

Replace `b.onTick(null)` with:
```java
AgentContext ctx = agentContexts.get(agent);
b.onTick(ctx);
```

**Step 4: Clean up on stop**

```java
@Override
public void stop(Agent agent) {
    agentBehaviors.remove(agent);
    agentContexts.remove(agent);
}
```

**Step 5: Verify compilation**

Run: `./gradlew :agent-os-reasoning-reactive:compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```
git add agent-os-reasoning-reactive/src/main/java/com/agentos/reasoning/reactive/ReactiveReasoningEngine.java
git commit -m "fix: pass AgentContext to reactive behaviors instead of null"
```

---

### Task 1.5: Fix SandboxedAgent thread-per-call resource leak

**Objective:** Replace per-call `Executors.newSingleThreadExecutor()` with a shared cached thread pool to avoid thread creation/destruction storms.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/sandbox/SandboxedAgent.java:100-145`

**Step 1: Add shared executor**

Add static field:
```java
private static final ExecutorService SANDBOX_EXECUTOR = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "sandbox-worker");
    t.setDaemon(true);
    return t;
});
```

**Step 2: Replace per-call executor**

Replace:
```java
ExecutorService executor = Executors.newSingleThreadExecutor(r -> { ... });
```
With:
```java
// Use shared pool — each call gets its own thread for isolation but threads are reused
```

Remove the `finally { executor.shutdownNow(); }` block since the shared executor persists.

**Step 3: Add a static shutdown method**

```java
public static void shutdownExecutor() {
    SANDBOX_EXECUTOR.shutdown();
}
```

**Step 4: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/sandbox/SandboxedAgent.java
git commit -m "fix: use shared thread pool in SandboxedAgent to prevent resource leaks"
```

---

### Task 1.6: Fix BuiltinActions fallthrough in resolveAgent

**Objective:** Remove the hardcoded `search("health-check")` fallback. Replace with proper multi-step resolution.

**Files:**
- Modify: `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BuiltinActions.java:31-40`

**Step 1: Replace the resolveAgent method**

```java
private AgentId resolveAgent(String name) {
    // 1. Try ServiceDirectory by service type
    var services = ctx.services();
    if (services != null) {
        var providers = services.search(name);
        if (!providers.isEmpty()) return providers.get(0).provider();
    }
    // 2. Try AgentRegistry by name
    var registry = ctx.registry();
    if (registry != null) {
        var info = registry.lookup(name);
        if (info.isPresent()) return info.get().agentId();
    }
    // 3. Fallback: create AgentId by name
    log.warn("BDI: agent '{}' not found in directory or registry, using literal name", name);
    return AgentId.of(name);
}
```

**Step 2: Add Logger to BuiltinActions**

Add:
```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
private static final Logger log = LoggerFactory.getLogger(BuiltinActions.class);
```

**Step 3: Verify compilation**

Run: `./gradlew :agent-os-reasoning-bdi:compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
git add agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BuiltinActions.java
git commit -m "fix: replace hardcoded health-check fallback with proper agent resolution"
```

---

## Phase 2: Logic Gap Closures (🟠 Major)

### Task 2.1: Fix init failure zombies — unregister on init failure

**Objective:** When `registerInternal` catches an init failure, properly clean up sessions, mailboxes, and registry entries.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java:274-282`

**Step 1: Replace the catch block**

Replace:
```java
} catch (Exception e) {
    session.transition(AgentLifecycle.TRANSIENT);
    log.warn("Agent {} init failed: {}", id.name(), e.getMessage());
    return;
}
```
With:
```java
} catch (Exception e) {
    log.warn("Agent {} init failed: {}", id.name(), e.getMessage());
    sessions.remove(id);
    mailboxes.remove(id);
    routingCache.invalidate(id.name());
    if (registry != null) {
        registry.unregister(id);
    }
    return;
}
```

**Step 2: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java
git commit -m "fix: properly clean up agent on init failure instead of leaving zombie"
```

---

### Task 2.2: Fix SandboxedAgent swallowing exceptions silently

**Objective:** When a sandboxed operation throws (non-violation), surface the error to the caller via the health system and log it properly. Currently the error is swallowed and the agent appears healthy.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/sandbox/SandboxedAgent.java:126-132`

**Step 1: Add a `lastError` field**

Add:
```java
private volatile Throwable lastError;
public Optional<Throwable> lastError() { return Optional.ofNullable(lastError); }
```

**Step 2: Store the error**

In the `ExecutionException` catch:
```java
lastError = cause;
```

**Step 3: Add `hasFailed()` convenience method**

```java
public boolean hasFailed() { return lastError != null; }
```

**Step 4: Clear error on successful operation**

At the top of `runSandboxed`, after obtaining `start`:
```java
lastError = null;
```

**Step 5: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/sandbox/SandboxedAgent.java
git commit -m "fix: surface sandboxed agent errors instead of silently swallowing"
```

---

### Task 2.3: Fix serializeState() — implement actual state serialization

**Objective:** `AgentSession.serializeState()` returns the agent's name as bytes. Implement proper state capture when the agent is a MobileAgent.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/AgentSession.java:97-99`

**Step 1: Replace serializeState**

```java
byte[] serializeState() {
    if (agent instanceof MobileAgent mobile) {
        try {
            return mobile.checkpoint();
        } catch (Exception e) {
            log.warn("Agent {} checkpoint failed: {}", agent.agentId().name(), e.getMessage());
        }
    }
    // Fallback: serialize basic identifying info
    return ("{\"name\":\"" + agent.agentId().name() + "\",\"id\":\"" + agent.agentId().id() + "\"}").getBytes(StandardCharsets.UTF_8);
}
```

**Step 2: Add import for StandardCharsets and MobileAgent**

```java
import java.nio.charset.StandardCharsets;
import com.agentos.kernel.MobileAgent;
```

**Step 3: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/AgentSession.java
git commit -m "fix: implement actual state serialization via MobileAgent.checkpoint()"
```

---

### Task 2.4: Fix BDI plan context evaluation to support boolean expressions

**Objective:** The context evaluator only handles `"true"` and simple literals. Add support for `&` (AND), `|` (OR), and `!` (NOT).

**Files:**
- Modify: `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/PlanLibrary.java:42-45`

**Step 1: Replace evaluateContext**

```java
private boolean evaluateContext(String context, BeliefBase beliefs) {
    if (context == null || context.isBlank() || "true".equalsIgnoreCase(context.trim())) {
        return true;
    }
    if (beliefs == null) return true;

    // Handle NOT: "!literal(...)"
    if (context.trim().startsWith("!")) {
        try {
            Literal lit = AslParser.parseLiteral(context.trim().substring(1));
            return !beliefs.holds(lit);
        } catch (Exception e) {
            return false;
        }
    }

    // Handle AND: "literal1(...) & literal2(...)"
    if (context.contains("&")) {
        String[] parts = context.split("&");
        for (String part : parts) {
            try {
                Literal lit = AslParser.parseLiteral(part.trim());
                if (!beliefs.holds(lit)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    // Handle OR: "literal1(...) | literal2(...)"
    if (context.contains("|")) {
        String[] parts = context.split("\\|");
        for (String part : parts) {
            try {
                Literal lit = AslParser.parseLiteral(part.trim());
                if (beliefs.holds(lit)) return true;
            } catch (Exception e) {
                // continue trying
            }
        }
        return false;
    }

    // Simple literal
    try {
        Literal ctxLit = AslParser.parseLiteral(context);
        return beliefs.holds(ctxLit);
    } catch (Exception e) {
        log.warn("BDI: unparseable context '{}', treating as false", context);
        return false;
    }
}
```

**Step 2: Add logger**

```java
private static final Logger log = LoggerFactory.getLogger(PlanLibrary.class);
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

**Step 3: Verify compilation**

Run: `./gradlew :agent-os-reasoning-bdi:compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
git add agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/PlanLibrary.java
git commit -m "fix: support AND/OR/NOT in BDI plan context evaluation"
```

---

### Task 2.5: Fix BDI plan failure — implement retry with alternative plans

**Objective:** When a plan fails, try the next relevant plan instead of silently dropping the goal.

**Files:**
- Modify: `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BdiReasoningEngine.java:165-178`

**Step 1: Track the plan index in executePlan**

Change `executePlan` to accept a list of candidate plans and an index, or use a different approach. Simpler: pass the triggering goal so we can re-query.

**Step 2: Replace the failure handling**

Replace:
```java
if (!success) {
    log.info("BDI: plan {} failed, checking for alternatives", plan.triggeringEvent());
    // For now, just log.
}
```
With:
```java
if (!success) {
    var lib = planLibraries.get(agent);
    var bb = beliefBases.get(agent);
    // Re-query for relevant plans (the failed one will be lower priority now or excluded)
    if (lib != null) {
        var alternatives = lib.relevant(Literal.of("alert", "high_cpu", "payment-service"), bb);
        // In a full implementation, we'd track which plans were tried and skip them.
        // For now, re-add the goal so it gets retried with alternatives on the next cycle.
        GoalBase gb = goalBases.get(agent);
        if (gb != null) {
            gb.addAchievementGoal(planGoal); // goal that triggered this plan
        }
        log.info("BDI: plan {} failed, goal re-queued for retry", plan.triggeringEvent());
    }
}
```

**Step 3: Track the goal that triggered the plan**

Add a field to map plan execution to goal:
```java
private final Map<Agent, Literal> activeGoal = new ConcurrentHashMap<>();
```

Set it before `executePlan` and read it in the failure handler.

**Step 4: Verify compilation**

Run: `./gradlew :agent-os-reasoning-bdi:compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
git add agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BdiReasoningEngine.java
git commit -m "fix: re-queue goal on BDI plan failure for alternative plan retry"
```

---

### Task 2.6: Fix SubscribeProtocol AGREE performative handling

**Objective:** The `AGREE` case is a comment with no code. Track the AGREED state.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/messaging/SubscribeProtocol.java:38-41`

**Step 1: Add AGREED state**

Change enum:
```java
private enum State { SUBSCRIBED, AGREED, REFUSED, CANCELLED }
```

**Step 2: Implement AGREE transition**

Replace:
```java
case AGREE -> {
    // AGREE confirms the subscription
}
```
With:
```java
case AGREE -> {
    if (current == State.SUBSCRIBED) {
        conversations.put(conversationId, State.AGREED);
    }
}
```

**Step 3: Update INFORM to require AGREED state**

```java
case INFORM -> {
    if (current != State.AGREED) {
        return violation(conversationId, "INFORM without AGREE after SUBSCRIBE");
    }
}
```

**Step 4: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/messaging/SubscribeProtocol.java
git commit -m "fix: implement AGREE state tracking in SubscribeProtocol"
```

---

### Task 2.7: Fix send() race condition — drain mailboxes atomically

**Objective:** The `send()` method delivers and drains in separate loops. Combine into a single loop to prevent interleaving.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java:354-400`

**Step 1: Merge deliver + drain into single loop**

Combine the two `for (AgentId receiver : msg.receivers())` loops into one that delivers and immediately drains:
```java
for (AgentId receiver : msg.receivers()) {
    AgentMailbox mailbox = mailboxes.get(receiver);
    if (mailbox != null) {
        mailbox.deliver(msg);
        mailbox.drain(failed -> {
            messagesFailed.incrementAndGet();
            sendFailure(msg, receiver, "dispatch error");
        });
    } else {
        // Not local — try transport
        if (transport != null) {
            sendWithRetry(msg, receiver);
        } else {
            messagesFailed.incrementAndGet();
            sendFailure(msg, receiver, "agent not found");
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java
git commit -m "fix: combine mailbox deliver+drain to prevent race condition"
```

---

### Task 2.8: Fix AgentMailbox.deliver() — dropping oldest silently loses the new message

**Objective:** When the mailbox is full, `deliver()` polls the oldest, then offers the new message but the return from `offer()` is returned as if the original offer succeeded. Fix the return semantics.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/AgentMailbox.java:25-32`

**Step 1: Fix deliver method**

```java
boolean deliver(ACLMessage msg) {
    boolean accepted = queue.offer(msg);
    if (!accepted) {
        ACLMessage oldest = queue.poll();
        if (oldest != null) {
            log.warn("Mailbox full ({}), dropped oldest msg conv={}", queue.size() + 1, oldest.conversationId());
        }
        accepted = queue.offer(msg);
    }
    return accepted;
}
```

**Step 2: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/AgentMailbox.java
git commit -m "fix: correct return value in AgentMailbox.deliver() when evicting oldest"
```

---

### Task 2.9: Fix migrate() — provide structured failure reasons

**Objective:** `migrate()` returns `CompletableFuture<Boolean>` — but `false` doesn't distinguish "agent vetoed" from "transport error". Return a richer result.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/AgentKernel.java`
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java`

**Step 1: Create MigrationResult record**

Create: `agent-os-kernel/src/main/java/com/agentos/kernel/MigrationResult.java`

```java
package com.agentos.kernel;

public record MigrationResult(boolean success, String reason) {
    public static MigrationResult ok() { return new MigrationResult(true, "migrated"); }
    public static MigrationResult vetoed() { return new MigrationResult(false, "agent vetoed"); }
    public static MigrationResult failed(String reason) { return new MigrationResult(false, reason); }
}
```

**Step 2: Update AgentKernel interface**

Change `migrate` return type:
```java
CompletableFuture<MigrationResult> migrate(AgentId id, String targetContainer);
```

**Step 3: Update DefaultAgentKernel.migrate()**

Use `MigrationResult` throughout.

**Step 4: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/MigrationResult.java
git add agent-os-kernel/src/main/java/com/agentos/kernel/AgentKernel.java
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java
git commit -m "feat: add structured MigrationResult instead of boolean return"
```

---

### Task 2.10: Fix BDI onMessage JSON parsing — avoid double extractJsonVal and ad-hoc unescaping

**Objective:** Clean up the fragile JSON parsing. Extract once, use proper JSON parsing.

**Files:**
- Modify: `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BdiReasoningEngine.java:74-90`

**Step 1: Extract values once**

Replace:
```java
String svc = extractJsonVal(content, "service") != null ? extractJsonVal(content, "service")
    : extractJsonVal(content.replace("\\\"", "\""), "service");
```
With:
```java
String svc = extractJsonVal(content, "service");
if (svc == null) {
    // Try with unescaped quotes
    svc = extractJsonVal(content.replace("\\\"", "\""), "service");
}
```

Do the same for `status` and `alert`.

**Step 2: Verify compilation**

Run: `./gradlew :agent-os-reasoning-bdi:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
git add agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BdiReasoningEngine.java
git commit -m "fix: avoid double extractJsonVal call in BDI onMessage JSON parsing"
```

---

### Task 2.11: Fix ASL parser to support multi-line plans

**Objective:** The regex uses `$` which restricts plans to single lines. Support multi-line plan bodies.

**Files:**
- Modify: `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/AslParser.java:9-11`

**Step 1: Replace line-by-line regex with section-based parsing**

Instead of regex, parse line by line:
```java
public static List<Plan> parse(String source) {
    List<Plan> plans = new ArrayList<>();
    String[] lines = source.split("\n");
    StringBuilder currentPlan = new StringBuilder();

    for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) continue;

        // Check if this line starts a new plan
        if (trimmed.matches("[+-]!?\\w+\\([^)]*\\)\\s*:.+")) {
            // Flush previous plan
            if (currentPlan.length() > 0) {
                parsePlanLine(currentPlan.toString()).ifPresent(plans::add);
            }
            currentPlan = new StringBuilder(trimmed);
        } else if (trimmed.endsWith(".")) {
            // End of current plan body
            currentPlan.append(" ").append(trimmed);
            parsePlanLine(currentPlan.toString()).ifPresent(plans::add);
            currentPlan = new StringBuilder();
        } else {
            // Continuation of body
            currentPlan.append(" ").append(trimmed);
        }
    }

    // Flush any remaining
    if (currentPlan.length() > 0) {
        parsePlanLine(currentPlan.toString()).ifPresent(plans::add);
    }

    return plans;
}

private static Optional<Plan> parsePlanLine(String line) {
    Matcher m = Pattern.compile(
        "([+-]!?\\w+\\([^)]*\\))\\s*:\\s*(.+?)\\s*<-\\s*(.+?)\\.\\s*$").matcher(line);
    if (m.find()) {
        return Optional.of(new Plan(m.group(1).trim(), m.group(2).trim(),
            parseBody(m.group(3).trim()), 0));
    }
    return Optional.empty();
}
```

**Step 2: Verify compilation**

Run: `./gradlew :agent-os-reasoning-bdi:compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```
git add agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/AslParser.java
git commit -m "fix: support multi-line BDI plans in ASL parser"
```

---

## Phase 3: Missing Endpoints & Features (🟡 Moderate)

### Task 3.1: Add /agents endpoint to list registered agents

**Objective:** Add a proper endpoint that lists all registered agents with their lifecycle and health.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java`

**Step 1: Add /agents context handler**

After `/health` handler, add:
```java
server.createContext("/agents", exchange -> {
    if (!checkAuth(exchange)) return;
    var health = healthSupplier.get();
    // For now, return agent counts + container info
    // Full agent list requires passing the session map
    String body = String.format(
        "{\"container\":\"%s\",\"activeAgents\":%d,\"suspendedAgents\":%d,\"terminatedAgents\":%d}\n",
        health.containerId(), health.activeAgents(), health.suspendedAgents(),
        health.terminatedAgents());
    sendJson(exchange, 200, body);
});
```

**Step 2: Update AgentsCmd in CLI to parse /agents**

In `AgentOsCtl.AgentsCmd`, change `parent.get("/health")` to `parent.get("/agents")`.

**Step 3: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava :agent-os-cli:compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java
git add agent-os-cli/src/main/java/com/agentos/cli/AgentOsCtl.java
git commit -m "feat: add /agents management endpoint"
```

---

### Task 3.2: Wire Kafka transport agent-to-container routing

**Objective:** When agents register/unregister, update the Kafka transport's PeerTable so messages are routed to specific containers instead of broadcast.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java`

**Step 1: Add PeerTable tracking**

The kernel should track which container an agent lives in and notify transports. For the local case this is `containerId`. When using Kafka transport, the transport needs to know container mappings.

Create a simple callback: add `registerAgent(String agentName, String containerId)` and `unregisterAgent(String agentName)` to `MessageTransport` interface as defaults:

```java
default void registerAgent(String agentName, String containerId) {}
default void unregisterAgent(String agentName) {}
```

**Step 2: Call transport on agent register/unregister**

In `registerInternal`, after adding to sessions:
```java
if (transport != null) {
    transport.registerAgent(id.name(), containerId);
}
```

In `unregister`:
```java
if (transport != null) {
    transport.unregisterAgent(id.name());
}
```

**Step 3: Implement in KafkaMessageTransport**

```java
@Override
public void registerAgent(String agentName, String containerId) {
    // Store mapping for targeted delivery
    agentToContainer.put(agentName, containerId);
}

@Override
public void unregisterAgent(String agentName) {
    agentToContainer.remove(agentName);
}
```

**Step 4: Use mapping in KafkaMessageTransport.send()**

When building target topics, check `agentToContainer` first.

**Step 5: Verify compilation**

Run: `./gradlew :agent-os-kernel:compileJava :agent-os-transport-kafka:compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```
git add agent-os-kernel/src/main/java/com/agentos/kernel/messaging/MessageTransport.java
git add agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java
git add agent-os-transport-kafka/src/main/java/com/agentos/transport/kafka/KafkaMessageTransport.java
git commit -m "fix: wire agent-to-container routing for Kafka transport"
```

---

### Task 3.3: Add per-agent health check capability

**Objective:** Add `AgentKernelHealth.agentHealth(AgentId id)` and a `/health/{agentId}` endpoint.

**Files:**
- Create: `agent-os-kernel/src/main/java/com/agentos/kernel/AgentHealth.java`
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/AgentKernel.java`
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java`
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java`

**Step 1: Create AgentHealth record**

```java
package com.agentos.kernel;

import java.time.Instant;

public record AgentHealth(
    String name,
    AgentLifecycle state,
    int consecutiveFailures,
    boolean sandboxed,
    long sandboxViolations,
    boolean hasError,
    Instant registeredAt
) {}
```

**Step 2: Add to AgentKernel interface**

```java
Optional<AgentHealth> agentHealth(AgentId id);
```

**Step 3: Implement in DefaultAgentKernel**

Look up session, extract data.

**Step 4: Add /health/{agentId} endpoint**

Parse path: `/health/payment-service` etc.

**Step 5: Verify compilation & commit**

---

## Phase 4: Sandbox Completion

### Task 4.1: Make sandboxEnabled config actually toggle sandboxing

**Objective:** When `config.sandboxEnabled()` is true, automatically wrap agents in SandboxedAgent on register.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java:246-250`

**Step 1: In registerInternal, wrap agent based on config**

At the start of `registerInternal`:
```java
Agent effectiveAgent = agent;
if (config.sandboxEnabled() && !(agent instanceof SandboxedAgent)) {
    var policy = switch (config.sandboxPolicy().toLowerCase()) {
        case "strict" -> SandboxPolicy.strict();
        case "permissive" -> SandboxPolicy.permissive();
        default -> SandboxPolicy.defaults();
    };
    effectiveAgent = new SandboxedAgent(agent, policy);
}
// Use effectiveAgent from here on
```

**Step 2: Update all references from `agent` to `effectiveAgent` in registerInternal**

**Step 3: Verify compilation & commit**

---

### Task 4.2: Enforce sandbox policy flags (file IO, network, system exit, runtime exec)

**Objective:** The four Boolean flags in SandboxPolicy are never checked. Add enforcement via SecurityManager or wrapper interceptors.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/sandbox/SandboxedAgent.java`
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/sandbox/SandboxPolicy.java`

**Step 1: Add check methods to SandboxPolicy**

```java
public void checkFileIo() {
    if (!allowFileIo) throw new SandboxViolationException("File IO not allowed");
}
public void checkNetwork() {
    if (!allowNetwork) throw new SandboxViolationException("Network not allowed");
}
// etc.
```

**Step 2: Add SecurityManager-based enforcement note**

Since SecurityManager is deprecated for removal, add a best-effort check in runSandboxed that wraps the task with policy pre-checks. Full enforcement would require a Java agent (bytecode instrumentation) — document this.

**Step 3: At minimum, intercept Runtime.exec and System.exit**

In `SandboxedAgent`, override lifecycle methods to check policy before delegating.

**Step 4: Verify compilation & commit**

---

### Task 4.3: Complete SandboxClassLoader JVM internal allowlist

**Objective:** The current `isJvmInternal` list is incomplete — missing `java.lang.Number`, `java.lang.CharSequence`, `java.lang.Comparable`, `java.lang.AutoCloseable`, `java.lang.Record`, `java.lang.System`, etc.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/sandbox/SandboxClassLoader.java:48-65`

**Step 1: Extend the allowlist**

Add all essential JVM types needed for basic agent operation.

**Step 2: Add package-level allowlist for java.lang and java.util**

Allow `java.lang.*` (basic types) and `java.util.*` (collections) unconditionally in the sandbox.

**Step 3: Verify compilation & commit**

---

## Phase 5: Hardening

### Task 5.1: Add structured error handling throughout KernelManagement

**Objective:** Replace `catch (Exception e) { sendJson(500, ...) }` with typed error responses that include error codes and timestamps.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/management/KernelManagement.java`

**Step 1: Create error response helper**

```java
private void sendError(HttpExchange exchange, int code, String errorCode, String message) {
    String body = String.format(
        "{\"error\":{\"code\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}}",
        errorCode, message, Instant.now().toString());
    sendJson(exchange, code, body);
}
```

**Step 2: Replace all `sendJson(exchange, code, "{\"error\":...}")` with sendError**

**Step 3: Verify compilation & commit**

---

### Task 5.2: Add graceful degradation when dependencies are missing

**Objective:** When Postgres/Kafka/gRPC are configured but unavailable, log warnings and fall back instead of crashing.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/impl/DefaultAgentKernel.java:start()`

**Step 1: Wrap ServiceLoader lookups in try-catch**

Each component should have a "not available" fallback.

**Step 2: Add dependency status to AgentKernelHealth**

Add fields: `persistenceAvailable`, `kafkaAvailable`, etc.

**Step 3: Verify compilation & commit**

---

### Task 5.3: Add configuration validation on startup

**Objective:** When config values are nonsensical (negative timeouts, zero ports), log warnings and use safe defaults.

**Files:**
- Modify: `agent-os-kernel/src/main/java/com/agentos/kernel/AgentOsConfig.java`

**Step 1: Add validate() method**

```java
public List<String> validate() {
    List<String> warnings = new ArrayList<>();
    if (tickInterval.toMillis() < 10) warnings.add("tickInterval < 10ms may cause excessive CPU");
    if (mailboxCapacity < 100) warnings.add("mailboxCapacity < 100 may cause message loss");
    // etc.
    return warnings;
}
```

**Step 2: Call validate() in start()**

Log all warnings on startup.

**Step 3: Verify compilation & commit**

---

### Task 5.4: Run full build + test suite

**Objective:** After all changes, run the complete build to catch regressions.

**Step 1: Full build**

Run: `./gradlew build -x :agent-os-persistence-postgres:test`
Expected: BUILD SUCCESSFUL (or identify remaining issues)

**Step 2: Run tests**

Run: `./gradlew test -x :agent-os-persistence-postgres:test`
Expected: All tests pass

**Step 3: Run demo**

Run: `./gradlew :agent-os-demo:run`
Expected: Demo runs successfully with new features

**Step 4: Final commit**

```
git add -A
git commit -m "feat: completion pass — 30 fixes across runtime, logic, endpoints, sandbox, hardening"
```

---

## Execution Order Summary

```
Phase 1 (6 tasks, ~30 min): 1.1 → 1.2 → 1.3 → 1.4 → 1.5 → 1.6
Phase 2 (11 tasks, ~55 min): 2.1 → 2.2 → 2.3 → 2.4 → 2.5 → 2.6 → 2.7 → 2.8 → 2.9 → 2.10 → 2.11
Phase 3 (3 tasks, ~15 min): 3.1 → 3.2 → 3.3
Phase 4 (3 tasks, ~15 min): 4.1 → 4.2 → 4.3
Phase 5 (4 tasks, ~20 min): 5.1 → 5.2 → 5.3 → 5.4
```

Total: 27 tasks, ~2.5 hours estimated implementation time.
