package com.agentos.reasoning.bdi;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AslParserTest {
    @Test
    void shouldParseSimplePlan() {
        String src = "+service_status(payment,down) : true <- .send(manager, inform, \"restart needed\").";
        var plans = AslParser.parse(src);
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).triggeringEvent()).isEqualTo("+service_status(payment,down)");
        assertThat(plans.get(0).body()).hasSize(1);
    }

    @Test
    void shouldParseMultiplePlans() {
        String src = """
            +service_status(payment,down) : true <- .send(manager, inform, "restart").
            +alert(high_cpu,Service) : true <- .send(sm, cfp, "scale").
            """;
        var plans = AslParser.parse(src);
        assertThat(plans).hasSize(2);
    }

    @Test
    void shouldParseLiteral() {
        var lit = AslParser.parseLiteral("service_status(payment, down)");
        assertThat(lit.predicate()).isEqualTo("service_status");
        assertThat(lit.terms()).containsExactly("payment", "down");
    }

    @Test
    void shouldParseNegatedLiteral() {
        var lit = AslParser.parseLiteral("not restart_in_progress(payment)");
        assertThat(lit.negated()).isTrue();
    }
}
