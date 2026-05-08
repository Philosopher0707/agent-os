package com.agentos.messaging;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import org.junit.jupiter.api.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class LocalMessageTransportTest {

    private LocalMessageTransport transport;

    @BeforeEach
    void setUp() {
        transport = new LocalMessageTransport();
    }

    @Test
    void shouldDeliverMessageToRegisteredMailbox() throws Exception {
        var queue = new LinkedBlockingQueue<ACLMessage>(10);
        var agentId = AgentId.of("test-agent");
        transport.registerMailbox(agentId, queue);

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("sender"))
            .receiver(agentId)
            .content("hello")
            .build();

        transport.send(msg);

        var received = queue.poll(2, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.content()).isEqualTo("hello");
    }

    @Test
    void shouldDropOldestWhenMailboxFull() throws Exception {
        var queue = new LinkedBlockingQueue<ACLMessage>(2);
        var agentId = AgentId.of("test-agent");
        transport.registerMailbox(agentId, queue);

        // Fill the queue
        for (int i = 0; i < 3; i++) {
            var msg = ACLMessage.builder()
                .performative(ACLMessage.Performative.INFORM)
                .sender(AgentId.of("sender"))
                .receiver(agentId)
                .content("msg-" + i)
                .build();
            transport.send(msg);
        }

        // Should have 2 messages (oldest dropped)
        assertThat(queue).hasSize(2);
        assertThat(queue.poll().content()).isEqualTo("msg-1");
        assertThat(queue.poll().content()).isEqualTo("msg-2");
    }

    @Test
    void shouldReturnLocalScheme() {
        assertThat(transport.scheme()).isEqualTo("local");
    }

    @Test
    void shouldClearMailboxesOnClose() {
        var queue = new LinkedBlockingQueue<ACLMessage>(10);
        var agentId = AgentId.of("test-agent");
        transport.registerMailbox(agentId, queue);
        transport.close();

        // After close, sending should not throw
        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("sender"))
            .receiver(agentId)
            .content("test")
            .build();
        transport.send(msg);
    }

    @Test
    void shouldDrainMailbox() throws Exception {
        var queue = new LinkedBlockingQueue<ACLMessage>(10);
        var agentId = AgentId.of("test-agent");
        transport.registerMailbox(agentId, queue);

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("sender"))
            .receiver(agentId)
            .content("drain-test")
            .build();
        transport.send(msg);

        var received = new LinkedBlockingQueue<ACLMessage>(10);
        transport.drainMailbox(agentId, received::offer, m -> {});

        var drained = received.poll(2, TimeUnit.SECONDS);
        assertThat(drained).isNotNull();
        assertThat(drained.content()).isEqualTo("drain-test");
        assertThat(queue).isEmpty();
    }
}
