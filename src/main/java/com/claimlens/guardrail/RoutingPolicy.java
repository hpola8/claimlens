package com.claimlens.guardrail;

import com.claimlens.claim.Decision;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides how much human attention a draft needs. Nothing is ever sent to a customer
 * automatically: FAST_TRACK means one-click approval, FULL_REVIEW means a reviewer must
 * read the policy evidence before acting.
 */
public final class RoutingPolicy {

    public enum Route { FAST_TRACK, FULL_REVIEW }

    public record Result(Route route, List<String> reasons) {
    }

    private final double minConfidence;
    private final BigDecimal highValueThreshold;

    public RoutingPolicy(double minConfidence, BigDecimal highValueThreshold) {
        this.minConfidence = minConfidence;
        this.highValueThreshold = highValueThreshold;
    }

    public Result route(Decision decision, double modelConfidence, BigDecimal amount, List<GuardrailCheck> checks) {
        List<String> reasons = new ArrayList<>();

        for (GuardrailCheck c : checks) {
            if (c.status() == GuardrailCheck.Status.FAIL) {
                reasons.add("Guardrail failed: " + c.label());
            }
        }
        if (decision == Decision.DENY) {
            reasons.add("Adverse decision: denials always need a human");
        }
        if (decision == Decision.NEEDS_INFO) {
            reasons.add("Draft asks for more information before deciding");
        }
        if (modelConfidence < minConfidence) {
            reasons.add(String.format("Model confidence %.2f is below %.2f", modelConfidence, minConfidence));
        }
        if (amount != null && amount.compareTo(highValueThreshold) > 0) {
            reasons.add("Claim amount exceeds $" + highValueThreshold.toPlainString());
        }

        if (reasons.isEmpty()) {
            return new Result(Route.FAST_TRACK, List.of("All guardrails passed; eligible for one-click approval"));
        }
        return new Result(Route.FULL_REVIEW, List.copyOf(reasons));
    }
}
