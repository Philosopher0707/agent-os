package com.agentos.kernel.impl;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class AgentMailboxTest {

    @Test
    void shouldDeliverMessage() {
        var received = new ArrayList<ACLMessage>();
        var mailbox = new AgentMailbox(10, received::add);
        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("s"))
            .receiver(AgentId.of("r"))
            .content("hello")
            .build();
        boolean ok = mailbox.deliver(msg);
        assertThat(ok).isTrue();
        assertThat(mailbox.size()).isEqualTo(1);
    }

    @Test
    void shouldDispatchOnDrain() {
        var dispatched = new ArrayList<ACLMessage>();
        var mailbox = new AgentMailbox(10, dispatched::add);
        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("s"))
            .receiver(AgentId.of("r"))
            .build();
        mailbox.deliver(msg);
        var overflow = new ArrayList<ACLMessage>();
        mailbox.drain(overflow::add);
        assertThat(dispatched).hasSize(1);
        assertThat(overflow).isEmpty();
        assertThat(mailbox.size()).isEqualTo(0);
    }

    @Test
    void shouldHandleOverflow() {
        var dispatched = new ArrayList<ACLMessage>();
        var mailbox = new AgentMailbox(2, dispatched::add);

        var m1 = ACLMessage.builder().performative(ACLMessage.Performative.INFORM).sender(AgentId.of("s")).receiver(AgentId.of("r")).build();
        var m2 = ACLMessage.builder().performative(ACLMessage.Performative.REQUEST).sender(AgentId.of("s")).receiver(AgentId.of("r")).build();
        var m3 = ACLMessage.builder().performative(ACLMessage.Performative.FAILURE).sender(AgentId.of("s")).receiver(AgentId.of("r")).build();

        mailbox.deliver(m1);
        mailbox.deliver(m2);
        boolean ok = mailbox.deliver(m3);
        // New message should be accepted, but oldest (m1) is evicted
        assertThat(ok).isTrue();
        assertThat(mailbox.size()).isEqualTo(2);
    }
}
