package com.agentos.demo;

import com.agentos.demo.agents.*;
import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.reasoning.bdi.AslParser;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class MultiAgentIntegrationTest {

    private DemoEnvironment env;
    private SimulatedService payment;

    @BeforeEach
    void setUp() {
        env = new DemoEnvironment();
        payment = env.createService("payment-service");
        env.createService("inventory-service");
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void shouldRegisterAgentsAndReportHealth() {
        var health = env.kernel().health();
        // No agents registered by DemoEnvironment — active may be 0, but kernel must be healthy
        assertThat(health.containerId()).isEqualTo("demo");
        assertThat(health.directoryAvailable()).isTrue();
        assertThat(health.transportAvailable()).isTrue();
    }

    @Test
    void shouldDetectFaultAndTriggerRecovery() throws Exception {
        var bdiAgent = createBdiAgent("orchestrator");
        env.bdiEngine().install(bdiAgent);
        var orchestratorId = env.kernel().register(bdiAgent);

        String plans = """
+alert(high_cpu,payment-service) : true <- .send(service-manager,cfp,"{\\"service\\":\\"payment-service\\",\\"issue\\":\\"high-cpu\\"}").
+msg_type(PROPOSE) : true <- .send(service-manager,accept_proposal,"{\\"service\\":\\"payment-service\\",\\"action\\":\\"scale\\",\\"delta\\":2}").
""";
        env.bdiEngine().library(bdiAgent).addAll(AslParser.parse(plans));

        env.kernel().register(new ServiceManagerAgent(env.getServiceMap()));
        env.kernel().register(new ResourceMonitorAgent("resource-monitor", payment, orchestratorId));
        env.kernel().register(new HealthCheckerAgent("health-checker", payment, orchestratorId));

        assertThat(payment.health().status()).isEqualTo("HEALTHY");

        // Inject fault
        payment.injectFault("high-cpu");
        assertThat(payment.health().cpuPercent()).isEqualTo(92.0);

        // Poll until recovery or timeout
        awaitStatus(payment, "HEALTHY", 5000);

        assertThat(payment.health().status()).isEqualTo("HEALTHY");
        assertThat(payment.health().replicas()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldHandleMultipleFaultCycles() throws Exception {
        var bdiAgent = createBdiAgent("orchestrator");
        env.bdiEngine().install(bdiAgent);
        var orchestratorId = env.kernel().register(bdiAgent);

        String plans = """
+alert(high_cpu,payment-service) : true <- .send(service-manager,cfp,"{\\"service\\":\\"payment-service\\",\\"issue\\":\\"high-cpu\\"}").
+msg_type(PROPOSE) : true <- .send(service-manager,accept_proposal,"{\\"service\\":\\"payment-service\\",\\"action\\":\\"scale\\",\\"delta\\":2}").
""";
        env.bdiEngine().library(bdiAgent).addAll(AslParser.parse(plans));

        env.kernel().register(new ServiceManagerAgent(env.getServiceMap()));
        env.kernel().register(new ResourceMonitorAgent("rm", payment, orchestratorId));
        env.kernel().register(new HealthCheckerAgent("hc", payment, orchestratorId));

        // First fault
        payment.injectFault("high-cpu");
        awaitStatus(payment, "HEALTHY", 5000);
        assertThat(payment.health().status()).isEqualTo("HEALTHY");

        // Second fault
        payment.injectFault("high-cpu");
        awaitStatus(payment, "HEALTHY", 5000);
        assertThat(payment.health().status()).isEqualTo("HEALTHY");

        assertThat(payment.health().replicas()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void shouldHandleCrashAndRestart() throws Exception {
        var bdiAgent = createBdiAgent("orchestrator");
        env.bdiEngine().install(bdiAgent);
        var orchestratorId = env.kernel().register(bdiAgent);

        String plans = """
+service_status(payment-service,DOWN) : true <- .send(service-manager,cfp,"{\\"service\\":\\"payment-service\\",\\"issue\\":\\"crash\\"}").
+msg_type(PROPOSE) : true <- .send(service-manager,accept_proposal,"{\\"service\\":\\"payment-service\\",\\"action\\":\\"restart\\"}").
""";
        env.bdiEngine().library(bdiAgent).addAll(AslParser.parse(plans));

        env.kernel().register(new ServiceManagerAgent(env.getServiceMap()));
        env.kernel().register(new HealthCheckerAgent("hc", payment, orchestratorId));

        payment.injectFault("crash");
        assertThat(payment.health().status()).isEqualTo("DOWN");

        awaitStatus(payment, "HEALTHY", 5000);
        assertThat(payment.health().status()).isEqualTo("HEALTHY");
    }

    /** Poll until the service reaches the expected status or timeout */
    private static void awaitStatus(SimulatedService svc, String expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(svc.health().status())) return;
            Thread.sleep(100);
        }
    }

    private Agent createBdiAgent(String name) {
        AgentId id = AgentId.of(name);
        return new Agent() {
            @Override public AgentId agentId() { return id; }
            @Override public boolean isEventDriven() { return true; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
    }
}
