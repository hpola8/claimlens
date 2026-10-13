package com.claimlens.pipeline;

import com.claimlens.claim.ClaimDecision;
import com.claimlens.claim.ClaimRequest;
import com.claimlens.guardrail.GuardrailCheck;
import com.claimlens.guardrail.RoutingPolicy;

import java.time.Instant;
import java.util.List;

/** Everything a reviewer needs to judge the draft: the evidence, the draft, the checks and the route. */
public record PipelineResult(
        String claimId,
        Instant createdAt,
        ClaimRequest claim,
        List<RetrievedClause> retrieved,
        ClaimDecision draft,
        boolean abstained,
        List<GuardrailCheck> checks,
        RoutingPolicy.Route route,
        List<String> routeReasons,
        String promptVersion,
        String model,
        long latencyMs
) {

    /**
     * A clause returned by vector search.
     *
     * @param usedAsEvidence true if it cleared the similarity threshold and was sent to the model
     */
    public record RetrievedClause(String clauseId, String title, String text, double score, boolean usedAsEvidence) {
    }
}
