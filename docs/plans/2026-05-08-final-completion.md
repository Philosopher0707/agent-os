# Agent OS — Final Completion Plan

> **Goal:** Close the remaining gaps from the audit. Priority: bug fixes → test coverage → feature completion.

**Status:** 46 tests pass (31 unit + 15 hardening), 1 disabled

---

## Phase 1: Bug Fixes (Quick Wins)

### Task 1.1: Fix BDI re-entry guard → unblock concurrent healing test
**File:** `agent-os-reasoning-bdi/.../BdiReasoningEngine.java:96-112`
**Fix:** Separate goal/belief addition from intention processing. Only guard `processIntentions()`, not the entire onMessage.
**Verify:** Re-enable `@Disabled` test in `ProductionHardeningTest.concurrentFaultHealing`

### Task 1.2: Fix ConfigLoader priority bug
**File:** `agent-os-kernel/.../impl/DefaultAgentKernel.java:loadConfig()`
**Fix:** EnvConfigLoader should only win if it has actual overrides, not defaults. Or: check system properties first.

---

## Phase 2: Test Coverage

### Task 2.1: Add Kafka transport test
**File:** Create `agent-os-transport-kafka/src/test/.../KafkaMessageTransportTest.java`
**Approach:** Test serialization/deserialization, topic routing, agent-container mapping. Mock Kafka producer/consumer.

### Task 2.2: Add WebSocket transport test  
**File:** Create `agent-os-transport-websocket/src/test/.../WebSocketTransportTest.java`
**Approach:** Start embedded Jetty server on random port, connect client, send/receive messages.

### Task 2.3: Add CLI tests
**File:** Create `agent-os-cli/src/test/.../AgentOsCtlTest.java`
**Approach:** Start management server, test CLI commands against it.

### Task 2.4: Add Postgres to CI → unskip persistence tests
**File:** `.github/workflows/test.yml`
**Approach:** Add `services: postgres` container, remove `-x :agent-os-persistence-postgres:test`

---

## Phase 3: Feature Completion

### Task 3.1: Per-agent health endpoint
**Files:** `AgentKernelHealth` (add method), `KernelManagement` (add endpoint), `CLI` (add command)
**Approach:** Add `agentHealth(AgentId)` returning individual health, wire `/health/{agentId}`.

### Task 3.2: Implement MobileAgent example
**File:** Create `agent-os-demo/.../agents/MobileHealthCheckerAgent.java`
**Approach:** A HealthCheckerAgent that implements MobileAgent, demonstrating checkpoint/restore.

---

## Execution Order

```
1.1 BDI guard fix (10 min)
1.2 ConfigLoader priority (5 min) 
2.1 Kafka test (15 min)
2.2 WebSocket test (15 min)
2.3 CLI test (10 min)
2.4 Postgres in CI (5 min)
3.1 Per-agent health (15 min)
3.2 MobileAgent demo (10 min)
```
