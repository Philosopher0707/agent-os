package com.agentos.demo;

import com.agentos.kernel.*;
import com.agentos.kernel.impl.DefaultAgentKernel;
import com.agentos.directory.*;
import com.agentos.messaging.*;
import com.agentos.reasoning.reactive.*;
import com.agentos.reasoning.bdi.*;
import java.time.Duration;
import java.util.*;

public class DemoEnvironment implements AutoCloseable {
    private final AgentKernel kernel;
    private final ReactiveReasoningEngine reactiveEngine;
    private final BdiReasoningEngine bdiEngine;
    private final Map<String, SimulatedService> services = new LinkedHashMap<>();

    public DemoEnvironment() {
        this(9091);
    }

    public DemoEnvironment(int managementPort) {
        reactiveEngine = new ReactiveReasoningEngine();
        bdiEngine = new BdiReasoningEngine();
        // Build kernel with custom config for port isolation
        var config = new AgentOsConfig(
            Duration.ofMillis(100), Duration.ofSeconds(30),
            10_000, 3, Duration.ofSeconds(5), 5,
            Duration.ofSeconds(60), Duration.ofSeconds(30),
            managementPort, 10_000, null, 3600, false, "default"
        );
        kernel = DefaultAgentKernel.create("demo", config);
        kernel.bind(bdiEngine);
        kernel.bind(reactiveEngine);
        kernel.bind(new LocalMessageTransport());
        kernel.bind(new InMemoryAgentRegistry());
        kernel.bind(new InMemoryServiceDirectory());
        kernel.start();
    }

    public SimulatedService createService(String name) {
        var svc = new SimulatedService(name);
        services.put(name, svc);
        return svc;
    }

    public SimulatedService getService(String name) { return services.get(name); }
    public Map<String, SimulatedService> getServiceMap() { return services; }
    public AgentKernel kernel() { return kernel; }
    public ReactiveReasoningEngine reactiveEngine() { return reactiveEngine; }
    public BdiReasoningEngine bdiEngine() { return bdiEngine; }

    @Override
    public void close() { kernel.close(); }
}
