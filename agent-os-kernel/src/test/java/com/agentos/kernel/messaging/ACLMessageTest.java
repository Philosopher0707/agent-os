package com.agentos.kernel.messaging;

import com.agentos.kernel.AgentId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ACLMessageTest {

    @Test
    void shouldBuildMessageWithDefaults() {
        var sender = AgentId.of("sender");
        var receiver = AgentId.of("receiver");

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(sender)
            .receiver(receiver)
            .protocol("fipa-request")
            .content("{\"status\": \"ok\"}")
            .build();

        assertThat(msg.performative()).isEqualTo(ACLMessage.Performative.INFORM);
        assertThat(msg.sender()).isEqualTo(sender);
        assertThat(msg.receivers()).containsExactly(receiver);
        assertThat(msg.language()).isEqualTo("json");
        assertThat(msg.encoding()).isEqualTo("UTF-8");
        assertThat(msg.timestamp()).isNotNull();
        assertThat(msg.conversationId()).isNotEmpty();
    }

    @Test
    void shouldRejectNullPerformative() {
        assertThatThrownBy(() ->
            ACLMessage.builder().sender(AgentId.of("s")).receiver(AgentId.of("r")).build()
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullSender() {
        assertThatThrownBy(() ->
            ACLMessage.builder().performative(ACLMessage.Performative.INFORM).receiver(AgentId.of("r")).build()
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectEmptyReceivers() {
        assertThatThrownBy(() ->
            new ACLMessage(ACLMessage.Performative.INFORM, AgentId.of("s"), Set.of(),
                null, "c1", null, null, "json", "UTF-8", null, "hi", java.time.Instant.now())
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receiversShouldBeImmutable() {
        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("s"))
            .receiver(AgentId.of("r"))
            .build();
        assertThatThrownBy(() -> msg.receivers().add(AgentId.of("x")))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
