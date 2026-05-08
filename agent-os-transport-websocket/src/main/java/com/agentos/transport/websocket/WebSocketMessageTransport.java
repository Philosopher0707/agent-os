package com.agentos.transport.websocket;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.messaging.MessageTransport;
import com.agentos.messaging.MessageSerializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * WebSocket-based MessageTransport for browser agents, dashboards, and lightweight clients.
 *
 * Architecture:
 *   - Server: Jetty 11 WebSocket server on a configurable port
 *   - Client: Jetty 11 WebSocket client for connecting to peer containers
 *   - Messages are JSON-serialized ACLMessage over text frames
 *   - Each connection is identified by the remote agent/container ID
 *
 * Scheme: "ws" (plaintext) or "wss" (TLS — future)
 */
public final class WebSocketMessageTransport implements MessageTransport {
    private static final Logger log = LoggerFactory.getLogger(WebSocketMessageTransport.class);

    private final int port;
    private final String containerId;
    private final Map<String, String> peerUrls; // containerId -> ws://host:port
    private final Map<String, Session> peerSessions = new ConcurrentHashMap<>();
    private final Map<String, Session> clientSessions = new ConcurrentHashMap<>(); // agentId -> session
    private Consumer<ACLMessage> inboundHandler;
    private WebSocketClient wsClient;
    private Server server;
    private volatile boolean running;

    public WebSocketMessageTransport(int port, String containerId, Map<String, String> peerUrls) {
        this.port = port;
        this.containerId = containerId;
        this.peerUrls = Map.copyOf(peerUrls);
    }

    public WebSocketMessageTransport(int port, String containerId) {
        this(port, containerId, Map.of());
    }

    @Override
    public String scheme() {
        return "ws";
    }

    @Override
    public void start() {
        try {
            // --- Server ---
            server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(port);
            server.addConnector(connector);

            ServletContextHandler context = new ServletContextHandler(
                ServletContextHandler.SESSIONS);
            context.setContextPath("/");
            server.setHandler(context);

            // Initialize WebSocket components for programmatic usage (Jetty 11)
            context.addServletContainerInitializer(
                new org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer());

            // Register WebSocket servlet — use inner class for transport access
            AgentWebSocketServlet servlet = new AgentWebSocketServlet();
            context.addServlet(new org.eclipse.jetty.servlet.ServletHolder(servlet), "/ws");

            server.start();
            log.info("WebSocket server started on port {}", port);

            // --- Client: connect to peers ---
            wsClient = new WebSocketClient();
            wsClient.start();

            for (var entry : peerUrls.entrySet()) {
                String peerContainer = entry.getKey();
                String url = entry.getValue();
                try {
                    var future = wsClient.connect(new PeerWebSocketListener(peerContainer),
                        URI.create(url));
                    Session session = future.get(10, TimeUnit.SECONDS);
                    peerSessions.put(peerContainer, session);
                    log.info("Connected to peer {} at {}", peerContainer, url);
                } catch (Exception e) {
                    log.warn("Failed to connect to peer {} at {}: {}", peerContainer, url, e.getMessage());
                }
            }

            running = true;
        } catch (Exception e) {
            throw new RuntimeException("WebSocket transport start failed", e);
        }
    }

    @Override
    public CompletableFuture<Void> send(ACLMessage msg) {
        String json = MessageSerializer.toJson(msg);

        // Deliver to locally connected clients
        for (AgentId receiver : msg.receivers()) {
            Session clientSession = clientSessions.get(receiver.name());
            if (clientSession != null && clientSession.isOpen()) {
                try {
                    clientSession.getRemote().sendString(json);
                } catch (Exception e) {
                    log.warn("WebSocket send to local client {} failed: {}", receiver.name(), e.getMessage());
                }
            }
        }

        // Route to peer containers
        Set<String> targetPeers = new HashSet<>();
        for (AgentId receiver : msg.receivers()) {
            String name = receiver.name();
            int atIdx = name.indexOf('@');
            if (atIdx > 0) {
                targetPeers.add(name.substring(atIdx + 1));
            }
        }

        for (String peerContainer : targetPeers) {
            Session peerSession = peerSessions.get(peerContainer);
            if (peerSession != null && peerSession.isOpen()) {
                try {
                    peerSession.getRemote().sendString(json);
                } catch (Exception e) {
                    log.warn("WebSocket send to peer {} failed: {}", peerContainer, e.getMessage());
                }
            }
        }

        // If no specific target, broadcast to all peers
        if (targetPeers.isEmpty() && !peerSessions.isEmpty()) {
            for (var entry : peerSessions.entrySet()) {
                try {
                    if (entry.getValue().isOpen()) {
                        entry.getValue().getRemote().sendString(json);
                    }
                } catch (Exception e) {
                    log.warn("WebSocket broadcast to {} failed: {}", entry.getKey(), e.getMessage());
                }
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void receive(Consumer<ACLMessage> handler) {
        this.inboundHandler = handler;
    }

    @Override
    public void close() {
        running = false;
        for (Session s : clientSessions.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        for (Session s : peerSessions.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        clientSessions.clear();
        peerSessions.clear();
        try { wsClient.stop(); } catch (Exception ignored) {}
        try { server.stop(); } catch (Exception ignored) {}
        log.info("WebSocket transport closed");
    }

    // ──── WebSocket Servlet (server-side, inner class for transport access) ────

    private class AgentWebSocketServlet extends JettyWebSocketServlet {
        @Override
        public void configure(JettyWebSocketServletFactory factory) {
            factory.setIdleTimeout(Duration.ofMinutes(5));
            factory.setCreator((req, resp) -> {
                String query = req.getQueryString();
                String agentId = null;
                if (query != null && query.startsWith("agentId=")) {
                    agentId = query.substring(8);
                }
                return new AgentWebSocketListener(agentId);
            });
        }
    }

    /**
     * Handles incoming connections from agents/clients connecting to this server.
     */
    private class AgentWebSocketListener implements WebSocketListener {
        private final String agentId;
        private Session session;

        AgentWebSocketListener(String agentId) {
            this.agentId = agentId;
        }

        @Override
        public void onWebSocketConnect(Session session) {
            this.session = session;
            if (agentId != null) {
                clientSessions.put(agentId, session);
                log.info("WebSocket client connected: agent={}", agentId);
            } else {
                log.warn("WebSocket connection without agentId, closing");
                session.close();
            }
        }

        @Override
        public void onWebSocketText(String message) {
            if (agentId == null) return;
            MessageSerializer.fromJson(message).ifPresent(msg -> {
                if (inboundHandler != null) {
                    inboundHandler.accept(msg);
                }
            });
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            if (agentId != null) {
                clientSessions.remove(agentId);
                log.info("WebSocket client disconnected: agent={}", agentId);
            }
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            log.warn("WebSocket error for agent={}: {}", agentId,
                cause != null ? cause.getMessage() : "unknown");
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {}
    }

    /**
     * Handles connections to peer containers.
     */
    private class PeerWebSocketListener implements WebSocketListener {
        private final String peerContainer;

        PeerWebSocketListener(String peerContainer) {
            this.peerContainer = peerContainer;
        }

        @Override
        public void onWebSocketConnect(Session session) {
            log.info("Peer WebSocket opened: {}", peerContainer);
        }

        @Override
        public void onWebSocketText(String message) {
            MessageSerializer.fromJson(message).ifPresent(msg -> {
                if (inboundHandler != null) {
                    inboundHandler.accept(msg);
                }
            });
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            peerSessions.remove(peerContainer);
            log.warn("Peer WebSocket closed: {} ({} {})", peerContainer, statusCode, reason);
            // Attempt reconnection after delay
            if (running) {
                CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() -> {
                    if (running && peerUrls.containsKey(peerContainer)) {
                        try {
                            var future = wsClient.connect(
                                new PeerWebSocketListener(peerContainer),
                                URI.create(peerUrls.get(peerContainer)));
                            Session s = future.get(10, TimeUnit.SECONDS);
                            peerSessions.put(peerContainer, s);
                            log.info("Reconnected to peer {}", peerContainer);
                        } catch (Exception e) {
                            log.warn("Reconnection to peer {} failed: {}",
                                peerContainer, e.getMessage());
                        }
                    }
                });
            }
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            log.warn("Peer WebSocket error {}: {}", peerContainer,
                cause != null ? cause.getMessage() : "unknown");
        }

        @Override
        public void onWebSocketBinary(byte[] payload, int offset, int len) {}
    }

    // ──── End of inner classes ────
}
