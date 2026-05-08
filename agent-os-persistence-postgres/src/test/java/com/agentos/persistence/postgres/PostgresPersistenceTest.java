package com.agentos.persistence.postgres;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class PostgresPersistenceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("agentos_test")
        .withUsername("test")
        .withPassword("test");

    private static String jdbcUrl;
    private static String user;
    private static String password;

    @BeforeAll
    static void setUp() {
        jdbcUrl = postgres.getJdbcUrl();
        user = postgres.getUsername();
        password = postgres.getPassword();
    }

    @Test
    void shouldAppendAndReplayMessages() throws Exception {
        try (var store = new PostgresMessageStore(jdbcUrl, user, password)) {
            var msg = ACLMessage.builder()
                .performative(ACLMessage.Performative.INFORM)
                .sender(AgentId.of("sender"))
                .receiver(AgentId.of("agent-1"))
                .content("{\"status\":\"ok\"}")
                .protocol("fipa-request")
                .build();

            store.append(msg).toCompletableFuture().get(5, TimeUnit.SECONDS);

            var replayed = store.replay("agent-1", Instant.now().minusSeconds(60))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(replayed).hasSize(1);
            assertThat(replayed.get(0).content()).isEqualTo("{\"status\":\"ok\"}");
        }
    }

    @Test
    void shouldFilterByTime() throws Exception {
        try (var store = new PostgresMessageStore(jdbcUrl, user, password)) {
            var old = ACLMessage.builder()
                .performative(ACLMessage.Performative.INFORM)
                .sender(AgentId.of("s"))
                .receiver(AgentId.of("agent-2"))
                .content("old")
                .build();
            store.append(old).toCompletableFuture().get(5, TimeUnit.SECONDS);
            Thread.sleep(100);

            Instant cutoff = Instant.now();
            Thread.sleep(100);

            var recent = ACLMessage.builder()
                .performative(ACLMessage.Performative.REQUEST)
                .sender(AgentId.of("s"))
                .receiver(AgentId.of("agent-2"))
                .content("recent")
                .build();
            store.append(recent).toCompletableFuture().get(5, TimeUnit.SECONDS);

            var afterCutoff = store.replay("agent-2", cutoff).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(afterCutoff).hasSize(1);
            assertThat(afterCutoff.get(0).content()).isEqualTo("recent");
        }
    }

    @Test
    void shouldArchiveOldMessages() throws Exception {
        try (var store = new PostgresMessageStore(jdbcUrl, user, password)) {
            var msg = ACLMessage.builder()
                .performative(ACLMessage.Performative.INFORM)
                .sender(AgentId.of("s"))
                .receiver(AgentId.of("agent-3"))
                .content("to-delete")
                .build();
            store.append(msg).toCompletableFuture().get(5, TimeUnit.SECONDS);

            store.archiveBefore(Instant.now().plusSeconds(3600)).toCompletableFuture().get(5, TimeUnit.SECONDS);

            var empty = store.replay("agent-3", Instant.now().minusSeconds(3600))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(empty).isEmpty();
        }
    }

    @Test
    void shouldSaveAndLoadAgentState() throws Exception {
        try (var store = new PostgresAgentStateStore(jdbcUrl, user, password)) {
            var id = AgentId.of("agent-state-1");
            byte[] data = "checkpoint-data".getBytes();

            store.save(id, data).toCompletableFuture().get(5, TimeUnit.SECONDS);

            var loaded = store.load(id).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(loaded).isPresent();
            assertThat(loaded.get()).isEqualTo(data);
        }
    }

    @Test
    void shouldDeleteAgentState() throws Exception {
        try (var store = new PostgresAgentStateStore(jdbcUrl, user, password)) {
            var id = AgentId.of("agent-temp");
            store.save(id, "temp".getBytes()).toCompletableFuture().get(5, TimeUnit.SECONDS);
            store.delete(id).toCompletableFuture().get(5, TimeUnit.SECONDS);

            var loaded = store.load(id).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(loaded).isEmpty();
        }
    }

    @Test
    void shouldReturnEmptyForUnknownAgent() throws Exception {
        try (var store = new PostgresAgentStateStore(jdbcUrl, user, password)) {
            var result = store.load(AgentId.of("nonexistent")).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThat(result).isEmpty();
        }
    }
}
