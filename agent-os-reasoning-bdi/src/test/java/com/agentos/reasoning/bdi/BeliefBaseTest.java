package com.agentos.reasoning.bdi;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BeliefBaseTest {
    @Test
    void shouldAddAndHoldBelief() {
        var bb = new BeliefBase();
        bb.add(Literal.of("status", "ok"));
        assertThat(bb.holds(Literal.of("status", "ok"))).isTrue();
    }

    @Test
    void shouldHandleOpposingBeliefs() {
        var bb = new BeliefBase();
        bb.add(Literal.of("status", "ok"));
        bb.add(Literal.not("status", "ok"));
        assertThat(bb.holds(Literal.of("status", "ok"))).isFalse();
        assertThat(bb.holds(Literal.not("status", "ok"))).isTrue();
    }

    @Test
    void shouldRemoveBelief() {
        var bb = new BeliefBase();
        bb.add(Literal.of("status", "ok"));
        bb.remove(Literal.of("status", "ok"));
        assertThat(bb.holds(Literal.of("status", "ok"))).isFalse();
    }
}
