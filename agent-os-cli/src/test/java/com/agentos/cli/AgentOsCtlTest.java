package com.agentos.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.*;

class AgentOsCtlTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/health", exchange -> {
            String body = "{\"status\":\"UP\",\"container\":\"test\",\"activeAgents\":2}";
            exchange.sendResponseHeaders(200, body.length());
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });

        server.createContext("/metrics", exchange -> {
            String body = "# HELP agentos_messages_total Total messages routed\n";
            exchange.sendResponseHeaders(200, body.length());
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });

        server.createContext("/dlq", exchange -> {
            String body = "{\"messages\":[{\"conversationId\":\"c1\"}],\"total\":1}";
            exchange.sendResponseHeaders(200, body.length());
            exchange.getResponseBody().write(body.getBytes());
            exchange.close();
        });

        server.createContext("/dlq/replay", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("OK".getBytes());
            exchange.close();
        });

        server.createContext("/token", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("OK".getBytes());
            exchange.close();
        });

        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String run(String... args) throws Exception {
        var oldOut = System.out;
        var baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            var ctl = new AgentOsCtl();
            // Use reflection to set managementUrl (private field)
            var field = AgentOsCtl.class.getDeclaredField("managementUrl");
            field.setAccessible(true);
            field.set(ctl, "http://localhost:" + port);

            int exitCode = new CommandLine(ctl).execute(args);
            assertThat(exitCode).isEqualTo(0);
        } finally {
            System.setOut(oldOut);
        }
        return baos.toString();
    }

    @Test
    void shouldPrintHealth() throws Exception {
        String out = run("health");
        assertThat(out).contains("UP").contains("activeAgents");
    }

    @Test
    void shouldPrintMetrics() throws Exception {
        String out = run("metrics");
        assertThat(out).contains("agentos_messages_total");
    }

    @Test
    void shouldListDlq() throws Exception {
        String out = run("dlq", "list");
        assertThat(out).contains("conversationId");
    }

    @Test
    void shouldReplayDlq() throws Exception {
        String out = run("dlq", "replay");
        assertThat(out).contains("OK");
    }

    @Test
    void shouldIssueToken() throws Exception {
        String out = run("token", "alice", "-s", "shh");
        assertThat(out).contains("OK");
    }
}
