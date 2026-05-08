# Remaining Work Plan — agent-os

**Date:** 2026-05-08
**Scope:** Close all remaining gaps from the audit.
**Constraint:** All 46 existing tests must continue to pass (31 unit + 15 hardening). Postgres CI already green.

---

## 1. Goal

Complete every remaining task from the original audit:
1. BDI concurrent healing — fix and re-enable the disabled hardening test
2. Kafka transport test suite
3. WebSocket transport test suite
4. CLI test suite
5. MobileAgent concrete demo

After all tasks: full test suite passes, CI green.

---

## 2. Current Context

### 2.1 BDI re-entry guard bug (TEST 16)
**File:** `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BdiReasoningEngine.java`
**Lines:** 98–120 (`onMessage` → `processIntentions` → `executePlan` → `.send()` → `onMessage` → `processing.add` returns `false` → goal queued but never executed until next tick)

**Root cause:**
- `onMessage()` adds goal to `GoalBase` then calls `processIntentions()`
- `processIntentions()` checks `processing.add(agent)` — if true, executes
- Inside `executePlan()`, `.send(service-manager, cfp, ...)` triggers `ServiceManagerAgent.handleCfp()` which sends `PROPOSE`
- `PROPOSE` arrives at BDI `onMessage()` but `processing` still contains the agent → `add()` returns `false`
- PROPOSE goal is added to `GoalBase` but `processIntentions()` is skipped
- After `executePlan()` returns, `processIntentions()` also returns → no one executes the queued PROPOSE goal
- HealthChecker/ResourceMonitor tick later adds more goals but processing guard still blocks

**Fix approach:** Replace the `processing` re-entry guard with a deferred execution queue:
1. `onMessage()` ALWAYS adds goals, NEVER calls `processIntentions()` inline
2. Add a `pendingAgents` queue (ConcurrentLinkedQueue)
3. A background single-threaded executor processes agents from the queue
4. The executor thread runs `processIntentions(agent)` then drains the queue again (in case new goals arrived during execution)
5. `step()` also submits to the queue instead of calling `processIntentions()` directly

This decouples goal addition from intention execution, eliminating the re-entrancy deadlock while preserving single-threaded per-agent semantics.

### 2.2 Kafka transport tests
**File to create:** `agent-os-transport-kafka/src/test/java/com/agentos/transport/kafka/KafkaMessageTransportTest.java`
**Dependencies already present:** `org.testcontainers:kafka`, `org.testcontainers:junit-jupiter`

Test cases:
- `shouldReturnKafkaScheme()` — asserts `scheme()` returns `"kafka"`
- `shouldSendToInboxTopicForKnownAgent()` — register an agent to a container, send message to that agent, verify Kafka producer sends to `agentos.messages.{containerId}` topic
- `shouldSendToBroadcastForUnknownAgent()` — send to agent with no container hint, verify broadcast topic used
- `shouldRouteUsingAgentToContainerMap()` — `registerAgent("alice", "container2")`, send to alice, verify `agentos.messages.container2` topic
- `shouldConsumeAndDeliverMessage()` — produce a message to the transport's inbox topic, verify `receive()` handler is called
- `shouldCloseWithoutErrors()` — `close()` should not throw

Approach: Use `@Testcontainers` + `@Container KafkaContainer`. Start transport, send via `transport.send()`, verify with a spy/mock on `KafkaProducer` OR by creating a second consumer to verify the record was published. For consume tests, produce a record directly to the topic and verify the receive handler fires.

**Risk:** Testcontainers Kafka startup is slow (~30s). May need `@Timeout(120)` on the class.

### 2.3 WebSocket transport tests
**File to create:** `agent-os-transport-websocket/src/test/java/com/agentos/transport/websocket/WebSocketMessageTransportTest.java`
**Dependencies already present:** `websocket-jetty-server`, `websocket-jetty-client`

Test cases:
- `shouldReturnWsScheme()` — asserts `scheme()` returns `"ws"`
- `shouldStartServerOnConfiguredPort()` — start transport on random port (0), verify server is listening
- `shouldDeliverMessageToConnectedClient()` — start server, connect a client WebSocket to `/ws?agentId=alice`, send a message via `transport.send()` with receiver=alice, verify client receives JSON
- `shouldReceiveMessageFromClient()` — connect client, send a message from client, verify `receive()` handler is called
- `shouldCloseGracefully()` — `close()` should stop server and client without exceptions

Approach: Use `WebSocketMessageTransport(port, containerId)` with an ephemeral port. For the client test, create a Jetty `WebSocketClient`, connect to `ws://localhost:{port}/ws?agentId=alice`, and use a `CountDownLatch` to wait for messages. Use `transport.receive(handler)` to capture inbound messages.

**Risk:** Port conflicts in parallel test runs. Use `ServerConnector.getLocalPort()` after start, or bind to port 0 and query the actual port.

### 2.4 CLI test suite
**File to create:** `agent-os-cli/src/test/java/com/agentos/cli/AgentOsCtlTest.java`
**Dependencies:** `junit-jupiter` already present. Add `org.mockito:mockito-core` + `mockito-junit-jupiter` to build.gradle.kts for HttpClient mocking.

Test cases:
- `health` — mock HttpClient to return `200 OK` with health JSON, verify command prints JSON
- `agents` — mock GET `/health`, verify output
- `inject-fault` — mock POST `/inject-fault`, verify request body contains correct agent and fault type
- `metrics` — mock GET `/metrics`, verify output
- `dlq list` — mock GET `/dlq?limit=20`, verify output
- `dlq replay` — mock POST `/dlq/replay`, verify output
- `dlq purge` — mock DELETE `/dlq`, verify output
- `token` — mock POST `/token`, verify output

Approach: The `AgentOsCtl` class has a private `HttpClient http` field and `get/post/delete` helper methods. For testing:
1. Make the `HttpClient` field package-private or add a package-visible constructor that accepts `HttpClient`
2. Use Mockito to mock `HttpClient` responses
3. Call `new CommandLine(agentOsCtl).execute("health")` and verify exit code = 0
4. Capture System.out with a `PrintStream` on a `ByteArrayOutputStream`

**Alternative (simpler):** Instead of mocking HttpClient, start a real `HttpServer` on a random port, register handlers, point AgentOsCtl at it. No Mockito dependency needed. This is cleaner for integration-style CLI tests.

### 2.5 MobileAgent concrete demo
**File to create:** `agent-os-demo/src/main/java/com/agentos/demo/agents/MigratableServiceAgent.java`

This is a `MobileAgent` implementation that:
- Wraps a `SimulatedService`
- `checkpoint()` — serializes service name, health status, and replica count to JSON bytes
- `restore()` — deserializes and updates the service state
- `prepareMigration()` — always returns `true`
- `afterMigration()` — logs the migration

**File to create:** `agent-os-demo/src/test/java/com/agentos/demo/MobileAgentTest.java`

Test cases:
- `shouldCheckpointAndRestore()` — checkpoint, modify service, restore, verify state recovered
- `shouldPrepareMigration()` — returns true
- `shouldMigrateViaKernel()` — register agent with kernel, call `kernel.migrate()`, verify agent is unregistered locally and migration message sent

---

## 3. Step-by-Step Execution Plan

### Phase 1: BDI re-entry guard fix (CRITICAL — unblocks Test 16)
**Files:**
- `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BdiReasoningEngine.java`
- `agent-os-demo/src/test/java/com/agentos/hardening/ProductionHardeningTest.java`

**Changes in BdiReasoningEngine:**
1. Replace `Set<Agent> processing` with `ConcurrentLinkedQueue<Agent> pendingQueue`
2. Add `ExecutorService bdiExecutor = Executors.newSingleThreadExecutor()` (or use `ForkJoinPool.commonPool()`)
3. In `install(agent)`: initialize the queue processing loop if not already started
4. In `onMessage()`: add goal to goal base, then `pendingQueue.offer(agent)`
5. In `step()`: `pendingQueue.offer(agent)` (if event-driven, skip since onMessage already queues)
6. The executor loop: `while (running) { Agent a = pendingQueue.poll(); if (a != null) processIntentions(a); }`
7. Inside `processIntentions()`: remove the `processing.add()` guard entirely — the queue ensures single-threaded execution per agent naturally (since we use a single thread, or if using multiple threads, we can use a per-agent lock)

Actually, simpler: use a single background thread for ALL agents. The `processIntentions()` call is already stateless per agent, so multiple agents can interleave safely. The only issue before was that `processIntentions()` was called recursively from within `executePlan()` via the same thread.

**Validation:**
- Run `ProductionHardeningTest.concurrentFaultHealing` — expect PASS
- Run all BDI unit tests — expect PASS

### Phase 2: Kafka transport tests
**Files:**
- `agent-os-transport-kafka/src/test/java/com/agentos/transport/kafka/KafkaMessageTransportTest.java` (new)

**Validation:**
- `./gradlew :agent-os-transport-kafka:test` — expect PASS (may be slow due to Testcontainers)

### Phase 3: WebSocket transport tests
**Files:**
- `agent-os-transport-websocket/src/test/java/com/agentos/transport/websocket/WebSocketMessageTransportTest.java` (new)

**Validation:**
- `./gradlew :agent-os-transport-websocket:test` — expect PASS

### Phase 4: CLI test suite
**Files:**
- `agent-os-cli/build.gradle.kts` — add Mockito
- `agent-os-cli/src/test/java/com/agentos/cli/AgentOsCtlTest.java` (new)
- `agent-os-cli/src/main/java/com/agentos/cli/AgentOsCtl.java` — add `HttpClient` constructor param for testability

**Validation:**
- `./gradlew :agent-os-cli:test` — expect PASS

### Phase 5: MobileAgent demo
**Files:**
- `agent-os-demo/src/main/java/com/agentos/demo/agents/MigratableServiceAgent.java` (new)
- `agent-os-demo/src/test/java/com/agentos/demo/MobileAgentTest.java` (new)

**Validation:**
- `./gradlew :agent-os-demo:test --tests "com.agentos.demo.MobileAgentTest"` — expect PASS

### Phase 6: Full regression
**Validation:**
- `./gradlew test -x :agent-os-persistence-postgres:test` — expect all PASS locally
- `./gradlew :agent-os-demo:test --tests "com.agentos.hardening.ProductionHardeningTest"` — expect all 16 PASS
- Push to GitHub, verify CI green including Postgres

---

## 4. Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| BDI queue refactor breaks existing single-threaded plan execution | High | High | Run all BDI unit tests after every incremental change; keep a backup of the original `processing` guard as fallback |
| Kafka Testcontainers too slow for CI timeout (15 min) | Medium | Medium | Add `@Timeout(120)` to class; if still too slow, skip in CI with `-Dtest.single=...` |
| WebSocket port conflicts in parallel tests | Medium | Low | Bind to port 0, query actual port from `ServerConnector` |
| Mockito version conflicts | Low | Low | Use `mockito-core:5.15.2` which works with JUnit 5.11 |
| BDI fix doesn't fully resolve concurrent healing | Medium | High | If the deferred queue approach fails, fall back to keeping test disabled and document the limitation |

---

## 5. File Change Summary

### Modified
1. `agent-os-reasoning-bdi/src/main/java/com/agentos/reasoning/bdi/BdiReasoningEngine.java`
2. `agent-os-demo/src/test/java/com/agentos/hardening/ProductionHardeningTest.java`
3. `agent-os-cli/src/main/java/com/agentos/cli/AgentOsCtl.java`
4. `agent-os-cli/build.gradle.kts`

### Created
5. `agent-os-transport-kafka/src/test/java/com/agentos/transport/kafka/KafkaMessageTransportTest.java`
6. `agent-os-transport-websocket/src/test/java/com/agentos/transport/websocket/WebSocketMessageTransportTest.java`
7. `agent-os-cli/src/test/java/com/agentos/cli/AgentOsCtlTest.java`
8. `agent-os-demo/src/main/java/com/agentos/demo/agents/MigratableServiceAgent.java`
9. `agent-os-demo/src/test/java/com/agentos/demo/MobileAgentTest.java`

---

## 6. Open Questions

None — all requirements are clear from the codebase and prior context.
