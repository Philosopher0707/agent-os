package com.agentos.transport.grpc;

import com.agentos.kernel.AgentId;
import com.agentos.kernel.messaging.ACLMessage;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class GrpcTransportTest {

    @Test
    void shouldSendAndReceiveBetweenTwoTransports() throws Exception {
        int portA = 19091 + new Random().nextInt(100);
        int portB = portA + 1;

        var transportA = new GrpcMessageTransport(portA, Map.of("b", "localhost:" + portB));
        var received = new LinkedBlockingQueue<ACLMessage>(1);
        transportA.receive(msg -> { try { received.put(msg); } catch (InterruptedException e) {} });
        transportA.start();

        var transportB = new GrpcMessageTransport(portB, Map.of("a", "localhost:" + portA));
        var receivedB = new LinkedBlockingQueue<ACLMessage>(1);
        transportB.receive(msg -> { try { receivedB.put(msg); } catch (InterruptedException e) {} });
        transportB.start();

        Thread.sleep(500);

        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.INFORM)
            .sender(AgentId.of("agent-a"))
            .receiver(AgentId.of("agent-b"))
            .content("hello from gRPC")
            .build();
        transportA.send(msg).get(2, TimeUnit.SECONDS);

        ACLMessage delivered = receivedB.poll(3, TimeUnit.SECONDS);
        assertThat(delivered).isNotNull();
        assertThat(delivered.content()).isEqualTo("hello from gRPC");

        transportA.close();
        transportB.close();
    }

    @Test
    void shouldConvertProtoRoundTrip() {
        var msg = ACLMessage.builder()
            .performative(ACLMessage.Performative.CFP)
            .sender(AgentId.of("orchestrator"))
            .receiver(AgentId.of("service-manager"))
            .protocol("fipa-contract-net")
            .content("{\"service\":\"payment\"}")
            .build();

        var proto = ProtoConverter.toProto(msg);
        var restored = ProtoConverter.fromProto(proto);

        assertThat(restored.performative()).isEqualTo(msg.performative());
        assertThat(restored.sender().name()).isEqualTo(msg.sender().name());
        assertThat(restored.content()).isEqualTo(msg.content());
    }

    @Test
    void peerTableShouldTrackPeers() {
        var table = PeerTable.fromMap(Map.of("c1", "10.0.0.1:9090", "c2", "10.0.0.2:9091"));
        assertThat(table.size()).isEqualTo(2);
        assertThat(table.get("c1")).isPresent();
        assertThat(table.get("c1").get().host()).isEqualTo("10.0.0.1");
    }
}
