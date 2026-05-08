package com.agentos.directory;

import com.agentos.kernel.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class InMemoryAgentRegistryTest {
    private InMemoryAgentRegistry registry;

    @BeforeEach
    void setUp() { registry = new InMemoryAgentRegistry(); }

    @Test
    void shouldRegisterAndLookup() throws AgentExistsException {
        Agent agent = makeAgent("agent-1");
        AgentId id = registry.register(agent, "c1");
        assertThat(id.name()).isEqualTo("agent-1");
        assertThat(registry.lookup("agent-1")).isPresent();
        assertThat(registry.lookup("agent-1").get().containerId()).isEqualTo("c1");
    }

    @Test
    void shouldRejectDuplicateName() throws AgentExistsException {
        registry.register(makeAgent("dup"), "c1");
        assertThatThrownBy(() -> registry.register(makeAgent("dup"), "c1"))
            .isInstanceOf(AgentExistsException.class);
    }

    @Test
    void shouldUnregister() throws AgentExistsException {
        AgentId id = registry.register(makeAgent("tmp"), "c1");
        registry.unregister(id);
        assertThat(registry.lookup("tmp")).isEmpty();
    }

    @Test
    void shouldTrackLifecycleState() throws AgentExistsException {
        AgentId id = registry.register(makeAgent("stateful"), "c1");
        assertThat(registry.getState(id)).isEqualTo(AgentLifecycle.INITIATED);
        registry.setState(id, AgentLifecycle.ACTIVE);
        assertThat(registry.getState(id)).isEqualTo(AgentLifecycle.ACTIVE);
    }

    @Test
    void shouldListByContainer() throws AgentExistsException {
        registry.register(makeAgent("a"), "c1");
        registry.register(makeAgent("b"), "c1");
        registry.register(makeAgent("c"), "c2");
        assertThat(registry.listByContainer("c1")).hasSize(2);
        assertThat(registry.listByContainer("c2")).hasSize(1);
    }

    private static Agent makeAgent(String name) {
        return new Agent() {
            @Override public AgentId agentId() { return AgentId.of(name); }
            @Override public void init(AgentContext ctx) {}
            @Override public void onMessage(com.agentos.kernel.messaging.ACLMessage msg) {}
            @Override public void suspend() {}
            @Override public void resume() {}
            @Override public void shutdown() {}
        };
    }
}
