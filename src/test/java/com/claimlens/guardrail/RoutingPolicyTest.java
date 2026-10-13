package com.claimlens.guardrail;

import com.claimlens.claim.Decision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPolicyTest {

    private final RoutingPolicy policy = new RoutingPolicy(0.75, new BigDecimal("10000"));
    private final List<GuardrailCheck> allPass = List.of(GuardrailCheck.pass("citations", "Citations are real", "ok"));

    @Test
    void cleanConfidentApprovalIsFastTracked() {
        var r = policy.route(Decision.APPROVE, 0.9, new BigDecimal("4800"), allPass);
        assertThat(r.route()).isEqualTo(RoutingPolicy.Route.FAST_TRACK);
    }

    @Test
    void denialsAlwaysNeedAHuman() {
        var r = policy.route(Decision.DENY, 0.99, new BigDecimal("500"), allPass);
        assertThat(r.route()).isEqualTo(RoutingPolicy.Route.FULL_REVIEW);
        assertThat(r.reasons()).anyMatch(s -> s.contains("Adverse"));
    }

    @Test
    void anyFailedGuardrailForcesFullReview() {
        var checks = List.of(GuardrailCheck.fail("injection", "No instructions hidden in claim", "hit"));
        var r = policy.route(Decision.APPROVE, 0.95, new BigDecimal("100"), checks);
        assertThat(r.route()).isEqualTo(RoutingPolicy.Route.FULL_REVIEW);
    }

    @Test
    void lowConfidenceAndHighValueAreBothReported() {
        var r = policy.route(Decision.APPROVE, 0.5, new BigDecimal("48000"), allPass);
        assertThat(r.route()).isEqualTo(RoutingPolicy.Route.FULL_REVIEW);
        assertThat(r.reasons()).hasSize(2);
    }
}
