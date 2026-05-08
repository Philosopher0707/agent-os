package com.agentos.transport.grpc;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

class PeerTable {
    record Peer(String containerId, String host, int port) {
        SocketAddress address() { return new InetSocketAddress(host, port); }
    }

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    // Maps agent names to the container they live in
    private final Map<String, String> agentToContainer = new ConcurrentHashMap<>();

    static PeerTable fromMap(Map<String, String> config) {
        var table = new PeerTable();
        for (var entry : config.entrySet()) {
            String[] parts = entry.getValue().split(":");
            table.add(new Peer(entry.getKey(), parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : 9090));
        }
        return table;
    }

    void add(Peer peer) { peers.put(peer.containerId(), peer); }

    Optional<Peer> get(String containerId) { return Optional.ofNullable(peers.get(containerId)); }

    Set<Peer> all() { return new HashSet<>(peers.values()); }

    int size() { return peers.size(); }

    /** Register which container an agent lives in */
    void registerAgent(String agentName, String containerId) {
        agentToContainer.put(agentName, containerId);
    }

    /** Unregister an agent */
    void unregisterAgent(String agentName) {
        agentToContainer.remove(agentName);
    }

    /** Resolve which peer hosts a given agent */
    Optional<Peer> resolve(String agentName) {
        String containerId = agentToContainer.get(agentName);
        if (containerId != null) {
            return get(containerId);
        }
        return Optional.empty();
    }
}
