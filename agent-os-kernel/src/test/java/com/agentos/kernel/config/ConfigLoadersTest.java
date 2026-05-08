package com.agentos.kernel.config;

import com.agentos.kernel.AgentOsConfig;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class ConfigLoadersTest {

    @Test
    void propertiesLoaderShouldUseDefaultsWhenNoProps() {
        var loader = new PropertiesConfigLoader();
        var config = loader.load();
        assertThat(config.tickInterval()).isEqualTo(AgentOsConfig.defaults().tickInterval());
    }

    @Test
    void envLoaderShouldUseDefaultsWhenNoVars() {
        var loader = new EnvConfigLoader();
        var config = loader.load();
        assertThat(config.stepTimeout()).isEqualTo(AgentOsConfig.defaults().stepTimeout());
    }

    @Test
    void propertiesLoaderShouldReadFromSystemProperty() {
        System.setProperty("agentos.tick.interval", "200ms");
        try {
            var loader = new PropertiesConfigLoader();
            var config = loader.load();
            assertThat(config.tickInterval()).isEqualTo(Duration.ofMillis(200));
        } finally {
            System.clearProperty("agentos.tick.interval");
        }
    }

    @Test
    void parseDurationShouldHandleFormats() {
        assertThat(PropertiesConfigLoader.parseDuration("100ms")).isEqualTo(Duration.ofMillis(100));
        assertThat(PropertiesConfigLoader.parseDuration("5s")).isEqualTo(Duration.ofSeconds(5));
        assertThat(PropertiesConfigLoader.parseDuration("2m")).isEqualTo(Duration.ofMinutes(2));
        assertThat(PropertiesConfigLoader.parseDuration("500")).isEqualTo(Duration.ofMillis(500));
    }
}
