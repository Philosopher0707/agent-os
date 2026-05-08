package com.agentos.demo;

import com.agentos.demo.agents.MigratableServiceAgent;
import com.agentos.kernel.*;
import com.agentos.kernel.impl.DefaultAgentKernel;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.messaging.LocalMessageTransport;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class MobileAgentTest {

    @Test
    void shouldCheckpointAndRestore() {
        var svc = new SimulatedService("payment");
        var agent = new MigratableServiceAgent("migrator", svc);

        byte[] state = agent.checkpoint();
        assertThat(new String(state)).contains("payment").contains("HEALTHY");

        svc.injectFault("high-cpu");
        assertThat(svc.health().status()).isEqualTo("DEGRADED");

        agent.restore(state);
        // restore logs but does not modify the service (that's up to the caller)
    }

    @Test
    void shouldPrepareMigration() {
        var agent = new MigratableServiceAgent("m1", new SimulatedService("s1"));
        assertThat(agent.prepareMigration()).isTrue();
    }

    @Test
    void shouldMigrateViaKernel() throws Exception {
        var config = new AgentOsConfig(
            Duration.ofMillis(100), Duration.ofSeconds(30),
            10_000, 3, Duration.ofSeconds(5), 5,
            Duration.ofSeconds(60), Duration.ofSeconds(30),
            29901, 10_000, null, 3600, false, "default");
        var kernel = DefaultAgentKernel.create("migrate-test", config);
        kernel.bind(new LocalMessageTransport());
        kernel.start();

        var svc = new SimulatedService("inventory");
        var agent = new MigratableServiceAgent("inv-agent", svc);
        AgentId id = kernel.register(agent);

        assertThat(id.name()).isEqualTo("inv-agent");
        assertThat(kernel.stateOf(id)).isEqualTo(AgentLifecycle.ACTIVE);

        var result = kernel.migrate(id, "target-container").get(10, TimeUnit.SECONDS);
        assertThat(result.success()).isTrue();

        // After migration the agent should be unregistered locally
        assertThat(kernel.stateOf(id)).isEqualTo(AgentLifecycle.TERMINATED);

        kernel.close();
    }
}
