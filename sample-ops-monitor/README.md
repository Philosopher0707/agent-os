# Sample: Ops Monitor — Real-World Agent OS Integration

A self-contained demo showing Agent OS monitoring a real HTTP API, detecting failures, negotiating remediation via FIPA-ACL Contract-Net protocol, and calling a REST API to fix the problem.

## What It Demonstrates

| Capability | How It's Used |
|---|---|
| **HTTP Integration** | `MonitorAgent` polls `/health`, `RemediationAgent` calls `POST /fix` |
| **FIPA Contract-Net** | CFP → PROPOSE → ACCEPT_PROPOSAL → INFORM |
| **Event-Driven Agent** | `RemediationAgent` is `isEventDriven=true`, responds to ACL messages |
| **Periodic Agent** | `MonitorAgent` ticks every 2s via `step()` |
| **Service Directory** | Agents register with Yellow Pages for discovery |
| **Management API** | View kernel health at `http://localhost:9095/health` |

## Run It

```bash
./gradlew :sample-ops-monitor:run
```

## Expected Output

```
External API: http://localhost:54231/health
Agent OS kernel started on management port 9095
Agents registered: monitor=monitor-agent, remediation=remediation-agent
monitor-agent API healthy (status=200, body={"status":"UP","requests":1})
monitor-agent API healthy (status=200, body={"status":"UP","requests":2})
monitor-agent API healthy (status=200, body={"status":"UP","requests":3})
monitor-agent API healthy (status=200, body={"status":"UP","requests":4})
ExternalApiSimulator: service BECAME UNHEALTHY at request #9
monitor-agent detected UNHEALTHY API (status=503, body={"status":"DOWN","requests":9})
monitor-agent → CFP → remediation-agent | negotiate remediation
remediation-agent received CFP from monitor-agent
remediation-agent → PROPOSE → monitor-agent | ready to fix
remediation-agent received ACCEPT from monitor-agent
ExternalApiSimulator: FIXED by agent (was request #9, now reset)
remediation-agent → INFORM → monitor-agent | fix succeeded
monitor-agent received INFORM from remediation-agent: {"result":"fixed","service":"external-api"}
monitor-agent API healthy (status=200, body={"status":"UP","requests":1})
Demo complete. API status: failing=false
```

## Variations to Try

### Add multiple monitors for different services
```java
kernel.register(new MonitorAgent("monitor-db", remediationId, dbHealthUrl));
kernel.register(new MonitorAgent("monitor-cache", remediationId, cacheHealthUrl));
```

### Replace RemediationAgent with BDI-only
Remove the `onMessage` implementation and add ASL plans via `BdiReasoningEngine`:
```java
bdi.install(remediationAgent);
bdi.library(remediationAgent).addAll(AslParser.parse("""
+cfp(unhealthy,service) : true <- .send(monitor-agent,propose,"{\"action\":\"restart\"}").
+msg_type(ACCEPT_PROPOSAL) : true <- .send(external-api,inform,"fix").
"""));
```

### Use the CLI to inspect during the run
```bash
./gradlew :agent-os-cli:installDist
./agent-os-cli/build/install/agent-os-cli/bin/agentosctl -m http://localhost:9095 health
./agent-os-cli/build/install/agent-os-cli/bin/agentosctl -m http://localhost:9095 agents
```
