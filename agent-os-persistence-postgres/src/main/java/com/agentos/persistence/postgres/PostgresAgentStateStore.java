package com.agentos.persistence.postgres;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.persistence.AgentStateStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class PostgresAgentStateStore implements AgentStateStore, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PostgresAgentStateStore.class);
    private final HikariDataSource ds;

    public PostgresAgentStateStore(String jdbcUrl, String user, String password) {
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
                CREATE TABLE IF NOT EXISTS agent_states (
                    agent_id TEXT PRIMARY KEY,
                    state BYTEA NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                )
                """);
        } catch (SQLException e) {
            throw new RuntimeException("Schema init failed", e);
        }
    }

    @Override
    public CompletionStage<Void> save(AgentId id, byte[] state) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO agent_states (agent_id, state, updated_at) VALUES (?, ?, NOW()) ON CONFLICT (agent_id) DO UPDATE SET state = ?, updated_at = NOW()";
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement(sql)) {
                ps.setString(1, id.name());
                ps.setBytes(2, state);
                ps.setBytes(3, state);
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("State save failed for {}: {}", id.name(), e.getMessage());
            }
        });
    }

    @Override
    public CompletionStage<Optional<byte[]>> load(AgentId id) {
        return CompletableFuture.supplyAsync(() -> {
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement("SELECT state FROM agent_states WHERE agent_id = ?")) {
                ps.setString(1, id.name());
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(rs.getBytes("state"));
                }
            } catch (SQLException e) {
                log.warn("State load failed for {}: {}", id.name(), e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletionStage<Void> delete(AgentId id) {
        return CompletableFuture.runAsync(() -> {
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement("DELETE FROM agent_states WHERE agent_id = ?")) {
                ps.setString(1, id.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.warn("State delete failed for {}: {}", id.name(), e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        ds.close();
    }
}
