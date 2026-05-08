package com.agentos.demo;

import com.agentos.demo.agents.*;
import com.agentos.kernel.*;
import com.agentos.kernel.messaging.ACLMessage;
import com.agentos.reasoning.bdi.AslParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        var env = new DemoEnvironment();
        var payment = env.createService("payment-service");
        env.createService("inventory-service");
        env.createService("notification-service");

        var bdiAgent = new Agent() {
            final AgentId id = AgentId.of("orchestrator");
            @Override public AgentId agentId() { return id; }
            @Override public boolean isEventDriven() { return true; }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };

        env.bdiEngine().install(bdiAgent);
        var orchestratorId = env.kernel().register(bdiAgent);

        String plans = """
+msg_from(resource-monitor) : true <- .println("got alert from resource-monitor").
+service_status(payment-service,DEGRADED) : true <- .send(service-manager,cfp,"{\\"service\\":\\"payment-service\\",\\"issue\\":\\"high-cpu\\"}").
+alert(high_cpu,payment-service) : true <- .send(service-manager,cfp,"{\\"service\\":\\"payment-service\\",\\"issue\\":\\"high-cpu\\"}").
+msg_type(PROPOSE) : true <- .send(service-manager,accept_proposal,"{\\"service\\":\\"payment-service\\",\\"action\\":\\"scale\\",\\"delta\\":2}").
""";
        env.bdiEngine().library(bdiAgent).addAll(AslParser.parse(plans));

        env.kernel().register(new ServiceManagerAgent(env.getServiceMap()));
        env.kernel().register(new HealthCheckerAgent("health-checker", payment, orchestratorId));
        env.kernel().register(new ResourceMonitorAgent("resource-monitor", payment, orchestratorId));
        env.kernel().register(new AlertDispatcherAgent());

        log.info("=== Agent OS Demo Started (BDI Orchestrator) ===");
        log.info("Plans loaded: {}", env.bdiEngine().library(bdiAgent).size());
        Thread.sleep(2000);

        log.info("\n--- Injecting high-cpu fault into payment-service ---");
        payment.injectFault("high-cpu");
        Thread.sleep(3000);

        log.info("\n=== Demo Complete ===");
        log.info("Service replicas: {}", payment.health().replicas());
        log.info("Service status: {}", payment.health().status());
        log.info("Final health: {}", env.kernel().health());
        env.close();
    }
}
