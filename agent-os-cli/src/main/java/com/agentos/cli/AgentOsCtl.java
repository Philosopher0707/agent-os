package com.agentos.cli;

import com.agentos.kernel.AgentKernelHealth;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * agentosctl — CLI management tool for Agent OS.
 *
 * Commands:
 *   health      — check kernel health
 *   agents      — list registered agents
 *   inject-fault — inject a fault into an agent
 *   metrics     — dump Prometheus metrics
 *   dlq         — inspect dead-letter queue
 *   token       — issue an auth token
 */
@Command(
    name = "agentosctl",
    description = "Agent OS management CLI",
    mixinStandardHelpOptions = true,
    version = "0.1.0",
    subcommands = {
        AgentOsCtl.HealthCmd.class,
        AgentOsCtl.AgentsCmd.class,
        AgentOsCtl.InjectFaultCmd.class,
        AgentOsCtl.MetricsCmd.class,
        AgentOsCtl.DlqCmd.class,
        AgentOsCtl.TokenCmd.class
    }
)
public class AgentOsCtl implements Callable<Integer> {

    @Option(names = {"-m", "--management-url"},
        description = "Management server URL (default: http://localhost:9091)")
    private String managementUrl = "http://localhost:9091";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @Override
    public Integer call() {
        System.out.println("agentosctl v0.1.0 — use --help for commands");
        return 0;
    }

    // ──── health ────

    @Command(name = "health", description = "Check kernel health")
    static class HealthCmd implements Callable<Integer> {
        @ParentCommand AgentOsCtl parent;

        @Override
        public Integer call() throws Exception {
            String body = parent.get("/health");
            System.out.println(body);
            return 0;
        }
    }

    // ──── agents ────

    @Command(name = "agents", description = "List registered agents")
    static class AgentsCmd implements Callable<Integer> {
        @ParentCommand AgentOsCtl parent;

        @Override
        public Integer call() throws Exception {
            String body = parent.get("/health");
            // Parse health JSON for agent counts
            System.out.println(body);
            return 0;
        }
    }

    // ──── inject-fault ────

    @Command(name = "inject-fault", description = "Inject a fault into an agent")
    static class InjectFaultCmd implements Callable<Integer> {
        @ParentCommand AgentOsCtl parent;

        @Parameters(index = "0", description = "Agent ID")
        private String agentId;

        @Option(names = {"-t", "--type"}, description = "Fault type: crash, hang, memory, slow")
        private String faultType = "crash";

        @Override
        public Integer call() throws Exception {
            String body = parent.post("/inject-fault",
                "{\"agent\":\"" + agentId + "\",\"type\":\"" + faultType + "\"}");
            System.out.println(body);
            return 0;
        }
    }

    // ──── metrics ────

    @Command(name = "metrics", description = "Dump Prometheus metrics")
    static class MetricsCmd implements Callable<Integer> {
        @ParentCommand AgentOsCtl parent;

        @Override
        public Integer call() throws Exception {
            String body = parent.get("/metrics");
            System.out.println(body);
            return 0;
        }
    }

    // ──── dlq ────

    @Command(name = "dlq", description = "Inspect or manage dead-letter queue",
        subcommands = {DlqCmd.ListCmd.class, DlqCmd.ReplayCmd.class, DlqCmd.PurgeCmd.class})
    static class DlqCmd implements Callable<Integer> {
        @ParentCommand AgentOsCtl parent;

        @Override public Integer call() { return 0; }

        @Command(name = "list", description = "List dead-lettered messages")
        static class ListCmd implements Callable<Integer> {
            @ParentCommand DlqCmd parent;
            @Option(names = {"-n", "--limit"}, description = "Max entries to show")
            private int limit = 20;

            @Override public Integer call() throws Exception {
                String body = parent.parent.get("/dlq?limit=" + limit);
                System.out.println(body);
                return 0;
            }
        }

        @Command(name = "replay", description = "Replay dead-lettered messages")
        static class ReplayCmd implements Callable<Integer> {
            @ParentCommand DlqCmd parent;
            @Option(names = {"-c", "--conversation"}, description = "Replay specific conversation only")
            private String conversationId;

            @Override public Integer call() throws Exception {
                String path = "/dlq/replay";
                if (conversationId != null) path += "?conversation=" + conversationId;
                String body = parent.parent.post(path, "");
                System.out.println(body);
                return 0;
            }
        }

        @Command(name = "purge", description = "Purge dead-letter queue")
        static class PurgeCmd implements Callable<Integer> {
            @ParentCommand DlqCmd parent;

            @Override public Integer call() throws Exception {
                String body = parent.parent.delete("/dlq");
                System.out.println(body);
                return 0;
            }
        }
    }

    // ──── token ────

    @Command(name = "token", description = "Issue an auth token")
    static class TokenCmd implements Callable<Integer> {
        @ParentCommand AgentOsCtl parent;

        @Parameters(index = "0", description = "Principal name")
        private String principal;

        @Option(names = {"-s", "--secret"}, description = "Shared secret", required = true)
        private String secret;

        @Override public Integer call() throws Exception {
            String body = parent.post("/token",
                "{\"principal\":\"" + principal + "\",\"secret\":\"" + secret + "\"}");
            System.out.println(body);
            return 0;
        }
    }

    // ──── HTTP helpers ────

    String get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(managementUrl + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    String post(String path, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(managementUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    String delete(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(managementUrl + path))
            .timeout(Duration.ofSeconds(10))
            .DELETE()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    // ──── entry point ────

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AgentOsCtl()).execute(args);
        System.exit(exitCode);
    }
}
