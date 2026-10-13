package com.claimlens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "claimlens")
public record ClaimLensProperties(Retrieval retrieval, Guardrails guardrails) {

    /**
     * @param topK                how many clauses to retrieve per claim
     * @param similarityThreshold minimum cosine similarity for a clause to be used as evidence
     */
    public record Retrieval(int topK, double similarityThreshold) {
    }

    /**
     * @param llmEvaluators       run Spring AI's relevancy + fact-checking evaluators on each draft
     * @param minModelConfidence  drafts below this self-reported confidence go to full review
     * @param highValueThreshold  claims above this amount go to full review
     */
    public record Guardrails(boolean llmEvaluators, double minModelConfidence, BigDecimal highValueThreshold) {
    }
}
