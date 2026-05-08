# Agent OS

**A multi-agent system (MAS) framework for autonomous, distributed agent orchestration — built in Java 21.**

Agent OS provides a kernel that manages agent lifecycles, FIPA-ACL message passing, service discovery, and pluggable reasoning engines (Reactive, BDI, LLM). Agents communicate via local or gRPC transport, persist state to PostgreSQL, and expose health/metrics via HTTP.

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      Agent OS Kernel                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────────┐  │
│  │ Lifecycle │ │ Messaging │ │ Directory│ │ Persistence │  │
│  │ Manager   │ │ (FIPA-ACL)│ │ (Yellow  │ │ (Postgres)  │  │
│  │           │ │           │ │  Pages)  │ │             │  │
│  └──────────┘ └──────────┘ └──────────┘ └────────────┘  │
│  ┌──────────────────────────────────────────────────┐    │
│  │              Reasoning Engines                    │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────────┐  │    │
│  │  │ Reactive  │ │   BDI    │ │       LLM        │  │    │
│  │  │ (pattern  │ │ (Belief- │ │ (LangChain4j +   │  │    │
│  │  │  match)   │ │ Desire-  │ │  tool dispatch)  │  │    │
│  │  │           │ │ Intention)│ │                  │  │    │
│  │  └──────────┘ └──────────┘ └──────────────────┘  │    │
│  └──────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────┐    │
│  │              Transports                           │    │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────────────┐  │    │
│  │  │  Local   │ │   gRPC   │ │      Kafka       │  │    │
│  │  │ (in-mem) │ │ (+ TLS)  │ │   (planned)      │  │    │
│  │  └──────────┘ └──────────┘ └──────────────────┘  │    │
│  └──────────────────────────────────────────────────┘    │
│  ┌──────────────────────────────────────────────────┐    │
│  │              Management (HTTP :9091)              │    │
│  │     /health    /ready    /metrics (Prometheus)    │    │
│  └──────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────┘
```

## Modules

| Module | Purpose |
|---|---|
| `agent-os-kernel` | Core: agent lifecycle, FIPA-ACL messaging, directory, config, management |
| `agent-os-messaging` | Local in-memory message transport |
| `agent-os-transport-grpc` | gRPC transport with TLS/mTLS + bidirectional streaming |
| `agent-os-directory` | In-memory AgentRegistry + ServiceDirectory (Yellow Pages) |
| `agent-os-config-yaml` | YAML-based configuration loader |
| `agent-os-reasoning-reactive` | Reactive reasoning (pattern-match → action) |
| `agent-os-reasoning-bdi` | BDI reasoning with ASL-like plan language |
| `agent-os-reasoning-llm` | LLM-based reasoning with LangChain4j + tool dispatch |
| `agent-os-persistence-postgres` | PostgreSQL message store + agent state store |
| `agent-os-demo` | Self-healing microservice orchestration demo |
| `agent-os-bom` | Bill of Materials |

## Quick Start

### Prerequisites
- Java 21
- Docker (for Postgres tests)

### Build
```bash
./gradlew build -x :agent-os-persistence-postgres:test
```

### Run the Demo
```bash
./gradlew :agent-os-demo:run
```

The demo simulates a self-healing microservice system:
1. Three services are created (payment, inventory, notification)
2. A BDI orchestrator agent monitors service health
3. A high-CPU fault is injected into the payment service
4. The orchestrator detects the fault, negotiates with a service manager, and scales the service
5. The service recovers to HEALTHY status

### Management Endpoints
```
curl http://localhost:9091/health   # Kernel health JSON
curl http://localhost:9091/ready    # Readiness check
curl http://localhost:9091/metrics  # Prometheus metrics
```

## Core Concepts

### Agent
Implement the `Agent` interface:
```java
public class MyAgent implements Agent {
    public AgentId agentId() { return AgentId.of("my-agent"); }
    public void init(AgentContext ctx) { /* register services, etc */ }
    public void onMessage(ACLMessage msg) { /* handle incoming messages */ }
    public void step() { /* periodic tick (if not event-driven) */ }
    public void suspend() { /* pause */ }
    public void resume() { /* resume */ }
    public void shutdown() { /* cleanup */ }
}
```

### ACL Messages (FIPA-compliant)
22 performatives: INFORM, REQUEST, CFP, PROPOSE, ACCEPT_PROPOSAL, REFUSE, FAILURE, SUBSCRIBE, etc.

```java
ACLMessage msg = ACLMessage.builder()
    .performative(ACLMessage.Performative.REQUEST)
    .sender(myId)
    .receiver(targetId)
    .protocol("fipa-request")
    .content("{\"action\":\"restart\"}")
    .build();
ctx.send(msg);
```

### Reasoning Engines

**Reactive** — pattern-match messages to behaviors:
```java
engine.addBehavior(agent, new Behavior() {
    public boolean matches(ACLMessage msg) { return msg.content().contains("alert"); }
    public CompletionStage<Void> handle(ACLMessage msg, AgentContext ctx) { ... }
});
```

**BDI** — write plans in an ASL-like DSL:
```
+alert(high_cpu,payment-service) : true <- .send(service-manager,cfp,"...")
+msg_type(PROPOSE) : true <- .send(service-manager,accept_proposal,"...")
```

**LLM** — let an LLM decide which tool to call:
```java
var engine = new LlmReasoningEngine(chatModel);
engine.addTool(agent, new LlmAgentTool() {
    public String name() { return "restart_service"; }
    public String description() { return "Restart a service by name"; }
    public String execute(String input, AgentContext ctx) { ... }
});
```

## Configuration

Via YAML (`agent-os.yaml`), environment variables, or system properties:

```yaml
tickInterval: 100ms
stepTimeout: 30s
mailboxCapacity: 10000
maxRetries: 3
consecutiveFailureLimit: 5
managementPort: 9091
```

## FIPA Protocol Validation

Built-in protocol validators:
- `ContractNetProtocol` — CFP → PROPOSE → ACCEPT/REJECT → INFORM/FAILURE
- `RequestProtocol` — REQUEST → AGREE/REFUSE → INFORM/FAILURE
- `SubscribeProtocol` — SUBSCRIBE → AGREE/REFUSE → INFORM* → CANCEL

## Docker

```bash
# Build the demo image
docker build -t agent-os-demo .

# Run with Docker Compose (multi-container)
docker-compose up
```

## License

Apache 2.0
