package com.agentos.transport.grpc;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.auth.TokenAuth;
import com.agentos.kernel.messaging.*;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class GrpcMessageTransport implements MessageTransport {
    private static final Logger log = LoggerFactory.getLogger(GrpcMessageTransport.class);

    private final PeerTable peers;
    private final int port;
    private final String containerId;
    private final boolean tlsEnabled;
    private final ChannelCredentials clientCreds;
    private final ServerCredentials serverCreds;
    private Server server;
    private Consumer<ACLMessage> inboundHandler;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, AgentMessagingGrpc.AgentMessagingBlockingStub> stubs = new ConcurrentHashMap<>();
    private final TokenAuth tokenAuth;

    // --- Plaintext constructor (backward compatible) ---
    public GrpcMessageTransport(int port, Map<String, String> peerConfig, String containerId) {
        this.port = port;
        this.containerId = containerId;
        this.peers = PeerTable.fromMap(peerConfig);
        this.tlsEnabled = false;
        this.clientCreds = null;
        this.serverCreds = null;
        this.tokenAuth = null;
    }

    public GrpcMessageTransport(int port, Map<String, String> peerConfig) {
        this(port, peerConfig, "default");
    }

    // --- Token auth constructor ---
    public GrpcMessageTransport(int port, Map<String, String> peerConfig, String containerId,
                                TokenAuth tokenAuth) {
        this.port = port;
        this.containerId = containerId;
        this.peers = PeerTable.fromMap(peerConfig);
        this.tlsEnabled = false;
        this.clientCreds = null;
        this.serverCreds = null;
        this.tokenAuth = tokenAuth;
    }

    // --- TLS constructor ---
    public GrpcMessageTransport(int port, Map<String, String> peerConfig, String containerId,
                                File certChainFile, File privateKeyFile, File trustCertFile) throws IOException {
        this(port, peerConfig, containerId, certChainFile, privateKeyFile, trustCertFile, null);
    }

    // --- TLS + token auth constructor ---
    public GrpcMessageTransport(int port, Map<String, String> peerConfig, String containerId,
                                File certChainFile, File privateKeyFile, File trustCertFile,
                                TokenAuth tokenAuth) throws IOException {
        this.port = port;
        this.containerId = containerId;
        this.peers = PeerTable.fromMap(peerConfig);
        this.tlsEnabled = true;
        this.tokenAuth = tokenAuth;

        TlsServerCredentials.Builder serverCredsBuilder = TlsServerCredentials.newBuilder()
            .keyManager(certChainFile, privateKeyFile);
        if (trustCertFile != null) {
            serverCredsBuilder.trustManager(trustCertFile);
            serverCredsBuilder.clientAuth(TlsServerCredentials.ClientAuth.REQUIRE);
        }
        this.serverCreds = serverCredsBuilder.build();

        TlsChannelCredentials.Builder clientCredsBuilder = TlsChannelCredentials.newBuilder()
            .keyManager(certChainFile, privateKeyFile);
        if (trustCertFile != null) {
            clientCredsBuilder.trustManager(trustCertFile);
        }
        this.clientCreds = clientCredsBuilder.build();
    }

    @Override public String scheme() { return tlsEnabled ? "grpcs" : "grpc"; }

    @Override
    public void start() {
        try {
            ServerBuilder<?> serverBuilder;
            if (tlsEnabled && serverCreds != null) {
                serverBuilder = Grpc.newServerBuilderForPort(port, serverCreds);
            } else {
                serverBuilder = ServerBuilder.forPort(port);
            }

            // Add token auth interceptor if configured
            if (tokenAuth != null) {
                serverBuilder.intercept(new TokenAuthInterceptor(tokenAuth));
                log.info("gRPC token authentication enabled");
            }

            server = serverBuilder
                .addService(new MessengerImpl())
                .build()
                .start();
            log.info("gRPC transport started on port {} ({})", port, scheme());
        } catch (Exception e) {
            throw new RuntimeException("gRPC server start failed", e);
        }
        for (var peer : peers.all()) {
            try {
                ManagedChannel channel;
                if (tlsEnabled && clientCreds != null) {
                    channel = Grpc.newChannelBuilder(peer.host() + ":" + peer.port(), clientCreds)
                        .build();
                } else {
                    channel = ManagedChannelBuilder.forAddress(peer.host(), peer.port())
                        .usePlaintext().build();
                }
                channels.put(peer.containerId(), channel);

                // Create stub with token interceptor if auth is configured
                var stub = AgentMessagingGrpc.newBlockingStub(channel);
                if (tokenAuth != null) {
                    String clientToken = tokenAuth.issueToken(containerId);
                    stub = stub.withInterceptors(new ClientTokenInterceptor(clientToken));
                }
                stubs.put(peer.containerId(), stub);
                log.info("Connected to peer {} at {}:{}", peer.containerId(), peer.host(), peer.port());
            } catch (Exception e) {
                log.warn("Failed to connect to peer {}", peer.containerId());
            }
        }
    }

    @Override
    public CompletableFuture<Void> send(ACLMessage msg) {
        var proto = ProtoConverter.toProto(msg);

        Set<String> targetPeers = new HashSet<>();
        for (AgentId receiver : msg.receivers()) {
            var peer = peers.resolve(receiver.name());
            peer.ifPresent(p -> targetPeers.add(p.containerId()));
        }

        if (targetPeers.isEmpty()) {
            targetPeers.addAll(peers.all().stream().map(PeerTable.Peer::containerId).toList());
        }

        for (String peerId : targetPeers) {
            var stub = stubs.get(peerId);
            if (stub != null) {
                try {
                    stub.send(proto);
                } catch (Exception e) {
                    log.warn("gRPC send to peer {} failed: {}", peerId, e.getMessage());
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override public void receive(Consumer<ACLMessage> handler) { this.inboundHandler = handler; }

    @Override
    public void close() {
        if (server != null) server.shutdown();
        channels.values().forEach(ManagedChannel::shutdown);
        log.info("gRPC transport shut down");
    }

    private class MessengerImpl extends AgentMessagingGrpc.AgentMessagingImplBase {
        @Override
        public void send(AclMessageProto request, StreamObserver<SendResponse> observer) {
            if (inboundHandler != null) {
                try {
                    inboundHandler.accept(ProtoConverter.fromProto(request));
                } catch (Exception e) {
                    log.warn("Inbound handler error: {}", e.getMessage());
                }
            }
            observer.onNext(SendResponse.newBuilder().setAcknowledged(true).build());
            observer.onCompleted();
        }

        @Override
        public StreamObserver<AclMessageProto> streamMessages(StreamObserver<AclMessageProto> responseObserver) {
            return new StreamObserver<>() {
                @Override
                public void onNext(AclMessageProto request) {
                    if (inboundHandler != null) {
                        try {
                            inboundHandler.accept(ProtoConverter.fromProto(request));
                            AclMessageProto ack = AclMessageProto.newBuilder()
                                .setPerformative("INFORM")
                                .setSenderName(containerId)
                                .setContent("{\"acknowledged\":true}")
                                .build();
                            responseObserver.onNext(ack);
                        } catch (Exception e) {
                            log.warn("Stream inbound error: {}", e.getMessage());
                        }
                    }
                }
                @Override public void onError(Throwable t) {
                    log.warn("Stream error: {}", t.getMessage());
                    responseObserver.onError(t);
                }
                @Override public void onCompleted() { responseObserver.onCompleted(); }
            };
        }
    }
}
