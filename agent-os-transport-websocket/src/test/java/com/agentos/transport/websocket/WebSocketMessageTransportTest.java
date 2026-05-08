package com.agentos.transport.websocket;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.messaging.MessageSerializer;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebSocketMessageTransportTest {

    private WebSocketMessageTransport transport;
    private WebSocketClient wsClient;
    private int actualPort;

    @BeforeAll
    void setUp() throws Exception {
        transport = new WebSocketMessageTransport(0, "test-container");
        transport.start();
        // Query the actual port assigned by Jetty
        var server = transport.getClass().getDeclaredField("server");
        server.setAccessible(true);
        var connectors = ((org.eclipse.jetty.server.Server) server.get(transport)).getConnectors();
        actualPort = ((org.eclipse.jetty.server.ServerConnector) connectors[0]).getLocalPort();

        wsClient = new WebSocketClient();
        wsClient.start();
    }

    @AfterAll
    void tearDown() {
        transport.close();
        try { wsClient.stop(); } catch (Exception ignored) {}
    }

    @Test
    void shouldReturnWsScheme() {
        assertThat(transport.scheme()).isEqualTo("ws");
    }

    @Test
    void shouldDeliverMessageToConnectedClient() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> payload = new AtomicReference<>();

        var listener = new WebSocketListener() {
            @Override public void onWebSocketConnect(Session session) {}
            @Override public void onWebSocketText(String message) {
                payload.set(message);
                received.countDown();
            }
            @Override public void onWebSocketClose(int statusCode, String reason) {}
            @Override public void onWebSocketError(Throwable cause) {}
            @Override public void onWebSocketBinary(byte[] payload, int offset, int len) {}
        };

        wsClient.connect(listener, URI.create("ws://localhost:" + actualPort + "/ws?agentId=alice")).get(5, TimeUnit.SECONDS);

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("bob"))
            .receiver(AgentId.of("alice"))
            .content("hello-websocket")
            .build();

        transport.send(msg);

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(payload.get()).contains("hello-websocket");
    }

    @Test
    void shouldReceiveMessageFromClient() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<ACLMessage> inbound = new AtomicReference<>();
        transport.receive(msg -> {
            if (msg.content().contains("from-client")) {
                inbound.set(msg);
                received.countDown();
            }
        });

        var sender = new org.eclipse.jetty.websocket.api.WebSocketListener() {
            Session session;
            @Override public void onWebSocketConnect(Session session) { this.session = session; }
            @Override public void onWebSocketText(String message) {}
            @Override public void onWebSocketClose(int statusCode, String reason) {}
            @Override public void onWebSocketError(Throwable cause) {}
            @Override public void onWebSocketBinary(byte[] payload, int offset, int len) {}
        };

        wsClient.connect(sender, URI.create("ws://localhost:" + actualPort + "/ws?agentId=charlie")).get(5, TimeUnit.SECONDS);
        Thread.sleep(100); // let server register client

        var outbound = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("charlie"))
            .receiver(AgentId.of("server"))
            .content("from-client")
            .build();
        String json = MessageSerializer.toJson(outbound);
        sender.session.getRemote().sendString(json);

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(inbound.get()).isNotNull();
        assertThat(inbound.get().content()).isEqualTo("from-client");
    }
}
