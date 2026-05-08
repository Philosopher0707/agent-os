package com.agentos.kernel;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AgentIdTest {

    @Test
    void shouldCreateWithNameAndId() {
        var id = UUID.randomUUID();
        var agentId = new AgentId("test-agent", id);
        assertThat(agentId.name()).isEqualTo("test-agent");
        assertThat(agentId.id()).isEqualTo(id);
    }

    @Test
    void shouldGenerateRandomId() {
        var a1 = AgentId.of("test-agent");
        var a2 = AgentId.of("test-agent");
        assertThat(a1.id()).isNotEqualTo(a2.id());
    }

    @Test
    void shouldRejectNullName() {
        assertThatThrownBy(() -> new AgentId(null, UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectBlankName() {
        assertThatThrownBy(() -> new AgentId("  ", UUID.randomUUID()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringShouldIncludeNameAndTruncatedId() {
        var agentId = AgentId.of("agent-1");
        assertThat(agentId.toString()).startsWith("agent-1[");
        assertThat(agentId.toString()).hasSizeLessThan(30);
    }
}
