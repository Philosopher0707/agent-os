rootProject.name = "agent-os"

include(
    "agent-os-bom",
    "agent-os-kernel",
    "agent-os-config-yaml",
    "agent-os-directory",
    "agent-os-messaging",
    "agent-os-transport-grpc",
    "agent-os-transport-kafka",
    "agent-os-transport-websocket",
    "agent-os-reasoning-reactive",
    "agent-os-reasoning-bdi",
    "agent-os-reasoning-llm",
    "agent-os-cli",
    "agent-os-demo",
    "sample-ops-monitor"
)
include("agent-os-persistence-postgres")
