# Agent OS — Pitch for Engineers & Decision Makers

Agent OS is a **Java 21 multi-agent runtime** for building autonomous agent systems that negotiate, coordinate, and self-heal across distributed infrastructure.

---

## The Problem

> "We have 200 microservices, 40 LLM agents, and a cron job that pages someone at 3 AM. The bots don't talk to each other, and we have no audit trail when they disagree."

Modern AI deployments are messy. You have:
- **LLM agents** making decisions with zero observability
- **SRE checks** that fire alerts but don't act
- **Workflow engines** that require humans to hand-code every branch
- **Separate systems** with no negotiation, no consensus, no recovery

What you actually need is a system where autonomous agents:
1. **sense** their environment (services, metrics, human requests)
2. **negotiate** with each other before acting (not blind coordination)
3. **execute** under audit, roll back on conflict
4. **heal** themselves when infrastructure fails

That system is multi-agent. Existing options are either too academic (JADE) or too narrow (LangChain).

---

## The Solution: Agent OS

Agent OS is not a library. It is a **kernel** — a runtime that hosts agents in a single address space while spanning clusters.

### Core Value Propositions

| Capability | What It Means |
|---|---|
| **FIPA-ACL Communication** | Agents speak a standardized negotiation language: "I propose to scale service X" → "I accept" → "Done." Not REST. Not gRPC between services. Communication *between reasoning actors*. |
| **Hybrid Reasoning** | BDI for auditable governance rules, LLM for adaptive natural-language tasks, Reactive for real-time events. Same agent, same kernel, three minds. |
| **Self-Healing Orchestration** | The demo ships with a production-hardened scenario: inject fault → heal → verify. Not just theory. |
| **Agent Mobility** | Checkpoint an agent's state, move it to another container, restore. Stateful agents follow work. |
| **Management Surface** | HTTP health/metrics/DLQ at :9091, CLI `agentosctl`, Prometheus-compatible. You can monitor the monitors. |
| **Sandboxed Execution** | Third-party agents run under configurable Java SecurityManager policies. Multi-tenant without trust. |

---

## Competitor Landscape

```
                    Complex Negotiation?
                         │
                         │
                         ▼
                   ┌──────────┐
     ┌─────────────┤ Agent OS ├─────────────┐
     │             └──────────┘             │
     │                                    │
Low ▼                                    ▼ High
Coordination                                  Reasoning
     │                                    │
┌────┴────┐  ┌────────┐  ┌──────────┐  ┌────┴─────┐
│Temporal │  │Airflow │  │LangChain │  │  JADE    │
│Dagster  │  │Camunda │  │ CrewAI   │  │ JaCaMo   │
└─────────┘  └────────┘  └──────────┘  └──────────┘
     │                                    │
     └────  What is this?  ──────────────┘
     Single-workflow, no negotiation   Pure research, no LLM,
     single-orchestrator               no cloud-native, deprecated
```

| Tool | Strength | Gap |
|---|---|---|
| **Temporal** | Reliable step-by-step workflows | No multi-actor negotiation, no LLM reasoning |
| **LangChain** | Easy LLM chaining | No agent lifecycles, no cross-agent protocols |
| **JADE** | Full FIPA compliance | Java 8, no LLM, no gRPC, no cloud, no sandboxing |
| **AutoGen** | Multi-agent conversation | Python-only, no sandboxing, no messaging guarantees |
| **CrewAI** | Multi-agent task flow | Pre-scripted tasks, no negotiation at runtime |
| **Agent OS** | Runtime *kernel* with negotiation, audit, healing, LLM, migration, safety | Young, JVM-only |

---

## Who Is This For?

### 1. Platform Engineering Teams
**Pain:** "We need to let internal teams deploy autonomous bots that can inspect and act on infrastructure, but we don't trust them to run arbitrary code alongside our services."

**Agent OS answer:** Sandboxed agents with SecurityManager policies. Each team's bot runs in isolation, communicates via ACL, and leaves an audit trail in Postgres.

### 2. AI-Native Infrastructure Teams
**Pain:** "Our LLM agents make decisions but we can't trace why they did what they did after an outage."

**Agent OS answer:** BDI plans are declarative and auditable. LLM reasoning can wrap deterministic BDI rules for high-stakes decisions. All ACL messages are persisted.

### 3. Edge & IoT Orchestration
**Pain:** "Our edge devices need to coordinate locally without round-tripping to the cloud, but they need to self-organize."

**Agent OS answer:** Lightweight agents in a single JVM, local discovery via ServiceDirectory, migration between edge nodes. gRPC transport with TLS.

### 4. Compliance & Regulatory Tech
**Pain:** "Our audit system needs agents that follow rules, not just statistically likely behavior."

**Agent OS answer:** BDI plans are deterministic, rule-based, and version-controllable. Every negotiation step (CFP → PROPOSE → ACCEPT → INFORM) is FIPA-protocol validated and logged.

---

## Architecture in One Diagram

```
┌────────────────── Agent OS Kernel ──────────────────────┐
│                                                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐ │
│  │  BDI     │  │  LLM     │  │  Reactive            │ │
│  │ Governor │  │ Solver   │  │  Sensor              │ │
│  │          │  │          │  │                      │ │
│  │ "If CPU  │  │ "Call    │  │ "Alert on           │ │
│  │  > 80%,  │  │  Stripe  │  │  SLA breach"         │ │
│  │  scale"  │  │  API"    │  │                      │ │
│  └──────────┘  └──────────┘  └──────────────────────┘ │
│         │            │                  │              │
│         ▼            ▼                  ▼              │
│  ┌──────────────────────────────────────────────┐     │
│  │  FIPA-ACL Bus                                 │     │
│  │  CFP/PROPOSE/ACCEPT/INFORM/REFUSE/FAILURE    │     │
│  └──────────────────────────────────────────────┘     │
│         │                    │                        │
│         ▼                    ▼                        │
│  ┌──────────┐         ┌──────────┐                    │
│  │  Local   │         │  gRPC/   │                    │
│  │  In-Mem  │         │  Kafka   │                    │
│  │  100K/s  │         │  Multi-  │                    │
│  │          │         │  cluster │                    │
│  └──────────┘         └──────────┘                    │
│                                                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐ │
│  │ Postgres │  │ Service  │  │ Management (:9091)   │ │
│  │ Store    │  │ Directory│  │ /health /metrics     │ │
│  └──────────┘  │(Yellow  │  │ /dlq   /inject-fault  │ │
│                │ Pages)   │  └──────────────────────┘ │
│                └──────────┘                             │
└───────────────────────────────────────────────────────┘
```

---

## What's Shipped Today

| Feature | Status | Notes |
|---|---|---|
| FIPA-ACL 22 performatives | ✅ | Complete |
| Contract-net, request, subscribe protocols | ✅ | Validated with hardening tests |
| BDI reasoning (ASL-like plans) | ✅ | Async execution, no re-entry deadlock |
| LLM reasoning (LangChain4j) | ✅ | Tool dispatch, streaming |
| Reactive reasoning | ✅ | Pattern-match behavior chains |
| gRPC transport + TLS | ✅ | Bidirectional streaming |
| Kafka transport | ✅ | Multi-cluster, container routing |
| WebSocket transport | ✅ | Browser agent support |
| Postgres persistence | ✅ | CI verified in GitHub Actions |
| Management HTTP API | ✅ | Health, metrics, DLQ, fault injection |
| CLI (`agentosctl`) | ✅ | Health, agents, metrics, DLQ, token |
| Agent sandboxing | ✅ | SecurityManager-based |
| Agent migration | ✅ | Full checkpoint/suspend/send/restore cycle |
| Self-healing demo | ✅ | 16/16 hardening tests pass |
| Per-agent health | ✅ | `/health/{agentId}` |
| Prometheus metrics | ✅ | Embedded HTTP server |

---

## Roadmap

| Quarter | Focus |
|---|---|
| **Q2** | Stability: 100% test coverage, BDI concurrent healing (done), Postgres CI (done) |
| **Q3** | Connectors: Kubernetes agent sensor, Stripe/GitHub webhook bridge, LangSmith trace export |
| **Q4** | Scale: Agent-to-agent encryption (S/MIME), consensus protocol (Paxos/Raft), Wasm agent runtime |
| **2027** | Ecosystem: Agent marketplace, visual plan editor, distributed ledger for agent attestation |

---

## Try It in 5 Minutes

```bash
git clone https://github.com/Philosopher0707/agent-os.git
cd agent-os
./gradlew :agent-os-demo:run
# open http://localhost:9091/health
# then inject a fault: ./gradlew :agent-os-cli:run --args="inject-fault payment-service -t crash"
```

---

## License

Apache 2.0 — use it, fork it, commit back.
