package com.agentos.directory;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.directory.ServiceDescription;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.*;

class InMemoryServiceDirectoryTest {

    private InMemoryServiceDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new InMemoryServiceDirectory();
    }

    @Test
    void shouldRegisterAndSearchService() {
        var provider = AgentId.of("agent-1");
        var desc = new ServiceDescription(provider, "health-check",
            Map.of("service", "payment"));

        directory.register(desc);
        var results = directory.search("health-check");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).provider().name()).isEqualTo("agent-1");
        assertThat(results.get(0).properties()).containsEntry("service", "payment");
    }

    @Test
    void shouldDeregisterService() {
        var provider = AgentId.of("agent-1");
        directory.register(new ServiceDescription(provider, "health-check", Map.of()));
        directory.deregister(provider, "health-check");

        assertThat(directory.search("health-check")).isEmpty();
    }

    @Test
    void shouldSearchWithConstraints() {
        directory.register(new ServiceDescription(AgentId.of("a1"), "worker",
            Map.of("region", "us-east")));
        directory.register(new ServiceDescription(AgentId.of("a2"), "worker",
            Map.of("region", "eu-west")));

        var results = directory.search("worker",
            props -> "us-east".equals(props.get("region")));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).provider().name()).isEqualTo("a1");
    }

    @Test
    void shouldNotifySubscribers() {
        var notifications = new CopyOnWriteArrayList<List<ServiceDescription>>();
        directory.subscribe("health-check", notifications::add);

        directory.register(new ServiceDescription(AgentId.of("a1"), "health-check", Map.of()));

        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0)).hasSize(1);
    }

    @Test
    void shouldUnsubscribe() {
        var notifications = new CopyOnWriteArrayList<List<ServiceDescription>>();
        var token = directory.subscribe("health-check", notifications::add);
        directory.unsubscribe(token);

        directory.register(new ServiceDescription(AgentId.of("a1"), "health-check", Map.of()));
        assertThat(notifications).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnknownService() {
        assertThat(directory.search("nonexistent")).isEmpty();
    }
}
