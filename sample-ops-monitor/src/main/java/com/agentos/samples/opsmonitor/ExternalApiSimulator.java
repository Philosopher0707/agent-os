package com.agentos.samples.opsmonitor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulates an external service that starts returning errors after a threshold.
 * The service can also be "fixed" via a POST to /fix.
 *
 * Endpoints:
 *   GET /health  → {"status":"UP"} (or 503 after failure threshold)
 *   POST /fix    → resets failure counter
 */
public final class ExternalApiSimulator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ExternalApiSimulator.class);
    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final int failAfter;     // how many requests before failure
    private final int failDuration;  // how many requests to stay failed

    public ExternalApiSimulator(int port, int failAfter, int failDuration) throws IOException {
        this.failAfter = failAfter;
        this.failDuration = failDuration;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/fix", this::handleFix);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
    }

    public void start() {
        server.start();
        log.info("ExternalApiSimulator started on port {}", server.getAddress().getPort());
    }

    public int port() { return server.getAddress().getPort(); }
    public boolean isFailing() { return failing.get(); }

    private void handleHealth(HttpExchange exchange) throws IOException {
        int count = requestCount.incrementAndGet();
        int failures = Math.max(failAfter, count - failAfter);

        if (count > failAfter && count <= failAfter + failDuration && !failing.get()) {
            failing.set(true);
            log.info("ExternalApiSimulator: service BECAME UNHEALTHY at request #{}", count);
        } else if (count > failAfter + failDuration && failing.get()) {
            log.info("ExternalApiSimulator: service would AUTO-RECOVER at request #{}", count);
        }

        String body;
        int status;
        if (failing.get()) {
            body = "{\"status\":\"DOWN\",\"requests\":" + count + "}";
            status = 503;
        } else {
            body = "{\"status\":\"UP\",\"requests\":" + count + "}";
            status = 200;
        }
        exchange.sendResponseHeaders(status, body.length());
        exchange.getResponseBody().write(body.getBytes());
        exchange.close();
    }

    private void handleFix(HttpExchange exchange) throws IOException {
        int before = requestCount.get();
        requestCount.set(0);
        failing.set(false);
        log.info("ExternalApiSimulator: FIXED by agent (was request #{}, now reset)", before);
        String body = "{\"fixed\":true}";
        exchange.sendResponseHeaders(200, body.length());
        exchange.getResponseBody().write(body.getBytes());
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        log.info("ExternalApiSimulator stopped");
    }
}
