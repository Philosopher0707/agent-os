package com.agentos.demo;

import com.agentos.demo.agents.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class DemoEnvironmentTest {
    @Test
    void shouldCreateServiceAndQueryHealth() {
        try (var env = new DemoEnvironment()) {
            var svc = env.createService("payment");
            assertThat(svc.health().status()).isEqualTo("HEALTHY");
            svc.injectFault("high-cpu");
            assertThat(svc.health().cpuPercent()).isEqualTo(92.0);
            svc.scale(2);
            assertThat(svc.health().status()).isEqualTo("HEALTHY");
        }
    }

    @Test
    void shouldCreateMultipleServices() {
        try (var env = new DemoEnvironment()) {
            env.createService("a");
            env.createService("b");
            assertThat(env.getServiceMap()).hasSize(2);
        }
    }
}
