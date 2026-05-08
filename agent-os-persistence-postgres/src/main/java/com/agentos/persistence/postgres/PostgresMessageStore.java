package com.agentos.persistence.postgres;

import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.kernel.persistence.MessageStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PostgresMessageStore implements MessageStore, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PostgresMessageStore.class);
    private final HikariDataSource ds;

    public PostgresMessageStore(String jdbcUrl, String user, String password) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        this.ds = new HikariDataSource(config);
        initSchema();
    }

    private void initSchema() {
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id BIGSERIAL PRIMARY KEY,
                    agent_id TEXT NOT NULL,
                    performative TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    content TEXT,
                    protocol TEXT,
                    conversation_id TEXT,
                    timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_msg_agent ON messages(agent_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_msg_timestamp ON messages(timestamp)");
        } catch (SQLException e) {
            throw new RuntimeException("Schema init failed", e);
        }
    }

    @Override
    public CompletionStage<Void> append(ACLMessage msg) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO messages (agent_id, performative, sender, content, protocol, conversation_id, timestamp) VALUES (?,?,?,?,?,?,?)";
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement(sql)) {
                for (var receiver : msg.receivers()) {
                    ps.setString(1, receiver.name());
                    ps.setString(2, msg.performative().name());
                    ps.setString(3, msg.sender().name());
                    ps.setString(4, msg.content());
                    ps.setString(5, msg.protocol());
                    ps.setString(6, msg.conversationId());
                    ps.setTimestamp(7, Timestamp.from(msg.timestamp()));
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                log.warn("Message append failed: {}", e.getMessage());
            }
        });
    }

    @Override
    public CompletionStage<List<ACLMessage>> replay(String agentId, Instant since) {
        return CompletableFuture.supplyAsync(() -> {
            List<ACLMessage> messages = new ArrayList<>();
            String sql = "SELECT performative, sender, content, protocol, conversation_id, timestamp FROM messages WHERE agent_id = ? AND timestamp >= ? ORDER BY timestamp ASC";
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement(sql)) {
                ps.setString(1, agentId);
                ps.setTimestamp(2, Timestamp.from(since));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messages.add(ACLMessage.builder()
                            .performative(ACLMessage.Performative.valueOf(rs.getString("performative")))
                            .sender(com.agentos.kernel.AgentId.of(rs.getString("sender")))
                            .receiver(com.agentos.kernel.AgentId.of(agentId))
                            .content(rs.getString("content"))
                            .protocol(rs.getString("protocol"))
                            .conversationId(rs.getString("conversation_id"))
                            .build());
                    }
                }
            } catch (SQLException e) {
                log.warn("Message replay failed: {}", e.getMessage());
            }
            return messages;
        });
    }

    @Override
    public CompletionStage<Void> archiveBefore(Instant cutoff) {
        return CompletableFuture.runAsync(() -> {
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement("DELETE FROM messages WHERE timestamp < ?")) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                int deleted = ps.executeUpdate();
                log.info("Archived {} messages before {}", deleted, cutoff);
            } catch (SQLException e) {
                log.warn("Archive failed: {}", e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        ds.close();
    }
}
