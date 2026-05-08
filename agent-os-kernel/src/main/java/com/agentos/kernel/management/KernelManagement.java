package com.agentos.kernel.management;

import com.agentos.kernel.AgentKernelHealth;
import com.agentos.kernel.AgentHealth;
import com.agentos.kernel.AgentOsConfig;
import com.agentos.kernel.auth.TokenAuth;
import com.agentos.kernel.impl.DeadLetterQueue;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Embedded HTTP management server exposing health, readiness, and Prometheus metrics.
 * Uses zero external dependencies — pure JDK.
 */
public final class KernelManagement implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KernelManagement.class);

    private final HttpServer server;
    private final int port;
    private final Supplier<AgentKernelHealth> healthSupplier;
    private final DeadLetterQueue deadLetterQueue;
    private final TokenAuth tokenAuth;
    private final java.util.function.BiConsumer<String, String> faultInjector;
    private final java.util.function.Consumer<com.agentos.kernel.messaging.ACLMessage> messageSender;
    private final java.util.function.Function<String, Optional<AgentHealth>> agentHealthProvider;
    private volatile boolean ready = false;

    // Kernel-level metrics
    private final LongAdder messagesRoutedTotal = new LongAdder();
    private final LongAdder messagesFailedTotal = new LongAdder();
    private final LongAdder messagesSentTotal = new LongAdder();

    public KernelManagement(int port, Supplier<AgentKernelHealth> healthSupplier,
                             DeadLetterQueue deadLetterQueue, TokenAuth tokenAuth) {
        this(port, healthSupplier, deadLetterQueue, tokenAuth, null, null, null);
    }

    public KernelManagement(int port, Supplier<AgentKernelHealth> healthSupplier,
                             DeadLetterQueue deadLetterQueue, TokenAuth tokenAuth,
                             java.util.function.BiConsumer<String, String> faultInjector,
                             java.util.function.Consumer<com.agentos.kernel.messaging.ACLMessage> messageSender,
                             java.util.function.Function<String, Optional<AgentHealth>> agentHealthProvider) {
        this.port = port;
        this.healthSupplier = healthSupplier;
        this.deadLetterQueue = deadLetterQueue;
        this.tokenAuth = tokenAuth;
        this.faultInjector = faultInjector;
        this.messageSender = messageSender;
        this.agentHealthProvider = agentHealthProvider;

        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create management server", e);
        }

        // --- /health (and /health/{agentId}) ---
        server.createContext("/health", exchange -> {
            String path = exchange.getRequestURI().getPath();
            // Check if this is a per-agent health request: /health/{agentId}
            if (path.length() > "/health".length() + 1) {
                String agentName = path.substring("/health/".length());
                if (agentHealthProvider != null) {
                    var agentHealth = agentHealthProvider.apply(agentName);
                    if (agentHealth.isPresent()) {
                        var h = agentHealth.get();
                        String body = String.format(
                            "{\"name\":\"%s\",\"state\":\"%s\",\"consecutiveFailures\":%d," +
                            "\"sandboxed\":%s,\"sandboxViolations\":%d,\"hasError\":%s}\n",
                            h.name(), h.state(), h.consecutiveFailures(),
                            h.sandboxed(), h.sandboxViolations(), h.hasError());
                        sendJson(exchange, 200, body);
                    } else {
                        sendJson(exchange, 404, "{\"error\":\"agent not found\"}");
                    }
                } else {
                    sendJson(exchange, 501, "{\"error\":\"per-agent health not configured\"}");
                }
                return;
            }
            var health = healthSupplier.get();
            String body = String.format(
                "{\"status\":\"UP\",\"container\":\"%s\",\"activeAgents\":%d,\"suspendedAgents\":%d," +
                "\"terminatedAgents\":%d,\"messagesRouted\":%d,\"messagesFailed\":%d," +
                "\"directoryAvailable\":%s,\"transportAvailable\":%s," +
                "\"sandboxedAgents\":%d,\"sandboxViolations\":%d}\n",
                health.containerId(), health.activeAgents(), health.suspendedAgents(),
                health.terminatedAgents(), health.messagesRouted(), health.messagesFailed(),
                health.directoryAvailable(), health.transportAvailable(),
                health.sandboxedAgents(), health.sandboxViolations());
            sendJson(exchange, 200, body);
        });

        // --- /ready ---
        server.createContext("/ready", exchange -> {
            String body = ready ? "{\"status\":\"READY\"}\n" : "{\"status\":\"NOT_READY\"}\n";
            sendJson(exchange, ready ? 200 : 503, body);
        });

        // --- /agents ---
        server.createContext("/agents", exchange -> {
            if (!checkAuth(exchange)) return;
            var health = healthSupplier.get();
            String body = String.format(
                "{\"container\":\"%s\",\"activeAgents\":%d,\"suspendedAgents\":%d,\"terminatedAgents\":%d}\n",
                health.containerId(), health.activeAgents(), health.suspendedAgents(),
                health.terminatedAgents());
            sendJson(exchange, 200, body);
        });

        // --- /metrics (Prometheus text format) ---
        server.createContext("/metrics", exchange -> {
            String body = scrapeMetrics();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // --- /dlq — dead-letter queue inspection ---
        server.createContext("/dlq", exchange -> {
            if (!checkAuth(exchange)) return;

            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                String query = exchange.getRequestURI().getQuery();
                int limit = 20;
                if (query != null && query.startsWith("limit=")) {
                    try { limit = Integer.parseInt(query.substring(6)); } catch (NumberFormatException ignored) {}
                }
                var entries = deadLetterQueue.peek(limit);
                StringBuilder sb = new StringBuilder("{\"entries\":[");
                boolean first = true;
                for (var e : entries) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append(String.format(
                        "{\"conversationId\":\"%s\",\"reason\":\"%s\",\"failedAt\":\"%s\",\"retryCount\":%d}",
                        e.message().conversationId(), e.reason(), e.failedAt(), e.retryCount()));
                }
                sb.append("],\"total\":" + deadLetterQueue.totalDeadLettered()
                    + ",\"current\":" + deadLetterQueue.currentSize() + "}");
                sendJson(exchange, 200, sb.toString());
            } else if ("DELETE".equals(method)) {
                int purged = deadLetterQueue.purge();
                sendJson(exchange, 200, "{\"purged\":" + purged + "}");
            } else {
                sendJson(exchange, 405, "{\"error\":\"method not allowed\"}");
            }
        });

        // --- /dlq/replay ---
        server.createContext("/dlq/replay", exchange -> {
            if (!checkAuth(exchange)) return;
            if (messageSender == null) {
                sendJson(exchange, 501, "{\"error\":\"DLQ replay not configured — no message sender\"}");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            int replayed;
            if (query != null && query.startsWith("conversation=")) {
                String convId = query.substring("conversation=".length());
                replayed = deadLetterQueue.replayConversation(convId, msg -> {
                    try { messageSender.accept(msg); return true; } catch (Exception e) { return false; }
                });
            } else {
                replayed = deadLetterQueue.replayAllWithSender(messageSender);
            }
            sendJson(exchange, 200, "{\"replayed\":" + replayed + "}");
        });

        // --- /token — issue auth token ---
        server.createContext("/token", exchange -> {
            if (tokenAuth == null) {
                sendJson(exchange, 501, "{\"error\":\"token auth not configured\"}");
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String principal = extractJsonVal(body, "principal");
                String secret = extractJsonVal(body, "secret");
                if (principal == null || principal.isBlank()) {
                    sendJson(exchange, 400, "{\"error\":\"principal required\"}");
                    return;
                }
                // Verify the requester knows the shared secret
                if (secret == null || !MessageDigest.isEqual(
                    secret.getBytes(StandardCharsets.UTF_8),
                    tokenAuth.sharedSecret().getBytes(StandardCharsets.UTF_8))) {
                    sendJson(exchange, 403, "{\"error\":\"invalid secret\"}");
                    return;
                }
                String token = tokenAuth.issueToken(principal);
                sendJson(exchange, 200, "{\"token\":\"" + token + "\"}");
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        // --- /inject-fault — fault injection for testing ---
        server.createContext("/inject-fault", exchange -> {
            if (!checkAuth(exchange)) return;
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 405, "{\"error\":\"POST required\"}");
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String agent = extractJsonVal(body, "agent");
                String type = extractJsonVal(body, "type");
                if (faultInjector != null) {
                    faultInjector.accept(agent, type);
                    sendJson(exchange, 200, "{\"injected\":true,\"agent\":\"" + agent
                        + "\",\"type\":\"" + type + "\"}");
                } else {
                    sendJson(exchange, 501, "{\"error\":\"fault injection not configured\"}");
                }
            } catch (Exception e) {
                sendJson(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "mgmt-http");
            t.setDaemon(true);
            return t;
        }));
    }

    // --- Metric recording API ---

    public void recordMessageRouted() { messagesRoutedTotal.increment(); }
    public void recordMessageFailed() { messagesFailedTotal.increment(); }
    public void recordMessageSent() { messagesSentTotal.increment(); }

    // --- Prometheus scrape ---

    private String scrapeMetrics() {
        StringBuilder sb = new StringBuilder();

        // Kernel metrics
        var health = healthSupplier.get();
        sb.append("# HELP agentos_messages_routed_total Total messages routed\n");
        sb.append("# TYPE agentos_messages_routed_total counter\n");
        sb.append("agentos_messages_routed_total ").append(health.messagesRouted()).append("\n");

        sb.append("# HELP agentos_messages_failed_total Total messages failed\n");
        sb.append("# TYPE agentos_messages_failed_total counter\n");
        sb.append("agentos_messages_failed_total ").append(health.messagesFailed()).append("\n");

        sb.append("# HELP agentos_active_agents Current active agents\n");
        sb.append("# TYPE agentos_active_agents gauge\n");
        sb.append("agentos_active_agents ").append(health.activeAgents()).append("\n");

        sb.append("# HELP agentos_suspended_agents Current suspended agents\n");
        sb.append("# TYPE agentos_suspended_agents gauge\n");
        sb.append("agentos_suspended_agents ").append(health.suspendedAgents()).append("\n");

        sb.append("# HELP agentos_terminated_agents Total terminated agents\n");
        sb.append("# TYPE agentos_terminated_agents counter\n");
        sb.append("agentos_terminated_agents ").append(health.terminatedAgents()).append("\n");

        sb.append("# HELP agentos_sandboxed_agents Current sandboxed agents\n");
        sb.append("# TYPE agentos_sandboxed_agents gauge\n");
        sb.append("agentos_sandboxed_agents ").append(health.sandboxedAgents()).append("\n");

        sb.append("# HELP agentos_sandbox_violations_total Total sandbox violations\n");
        sb.append("# TYPE agentos_sandbox_violations_total counter\n");
        sb.append("agentos_sandbox_violations_total ").append(health.sandboxViolations()).append("\n");

        // JVM Memory
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        sb.append("# HELP jvm_memory_used_bytes Used heap memory\n");
        sb.append("# TYPE jvm_memory_used_bytes gauge\n");
        sb.append("jvm_memory_used_bytes ").append(memory.getHeapMemoryUsage().getUsed()).append("\n");

        sb.append("# HELP jvm_memory_max_bytes Max heap memory\n");
        sb.append("# TYPE jvm_memory_max_bytes gauge\n");
        sb.append("jvm_memory_max_bytes ").append(memory.getHeapMemoryUsage().getMax()).append("\n");

        // JVM Threads
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        sb.append("# HELP jvm_threads_live Current live threads\n");
        sb.append("# TYPE jvm_threads_live gauge\n");
        sb.append("jvm_threads_live ").append(threads.getThreadCount()).append("\n");

        // JVM CPU
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        sb.append("# HELP jvm_cpu_load Process CPU load\n");
        sb.append("# TYPE jvm_cpu_load gauge\n");
        sb.append("jvm_cpu_load ").append(String.format("%.4f", os.getSystemLoadAverage())).append("\n");

        sb.append("# HELP jvm_cpu_cores Available processors\n");
        sb.append("# TYPE jvm_cpu_cores gauge\n");
        sb.append("jvm_cpu_cores ").append(Runtime.getRuntime().availableProcessors()).append("\n");

        return sb.toString();
    }

    private void sendJson(com.sun.net.httpserver.HttpExchange exchange, int code, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            log.warn("Failed to send response: {}", e.getMessage());
        }
    }

    /** Send a structured error response with error code and timestamp. */
    private void sendError(com.sun.net.httpserver.HttpExchange exchange, int code,
                            String errorCode, String message) {
        String body = String.format(
            "{\"error\":{\"code\":\"%s\",\"message\":\"%s\",\"timestamp\":\"%s\"}}",
            errorCode, message, Instant.now().toString());
        sendJson(exchange, code, body);
    }

    /**
     * Check Bearer token authentication on protected endpoints.
     * Returns true if authorized, false and sends 401 otherwise.
     */
    private boolean checkAuth(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (tokenAuth == null) return true; // no auth configured — allow all

        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        String token = TokenAuth.extractBearer(authHeader);
        if (token == null || tokenAuth.validate(token) == null) {
            sendJson(exchange, 401, "{\"error\":\"unauthorized\"}");
            return false;
        }
        return true;
    }

    private static String extractJsonVal(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) { search = "\"" + key + "\":"; start = json.indexOf(search); }
        if (start < 0) return null;
        start += search.length();
        char c = json.charAt(start);
        if (c == '"') { start++; int end = json.indexOf("\"", start); return end > start ? json.substring(start, end) : null; }
        int end = json.indexOf(",", start);
        if (end < 0) end = json.indexOf("}", start);
        return end > start ? json.substring(start, end).trim() : json.substring(start).trim();
    }

    // --- Lifecycle ---

    public void start() {
        server.start();
        ready = true;
        log.info("Management server started on port {} — /health /ready /metrics", port);
    }

    @Override
    public void close() {
        ready = false;
        server.stop(2);
        log.info("Management server stopped");
    }
}
