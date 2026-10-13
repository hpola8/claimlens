package com.claimlens.claim;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Structured output the LLM must return. Spring AI's {@code .entity(ClaimDecision.class)}
 * turns this record into a JSON schema that is sent with the prompt, and maps the reply back.
 */
public record ClaimDecision(

        @JsonPropertyDescription("APPROVE, DENY or NEEDS_INFO")
        Decision decision,

        @JsonPropertyDescription("Self-reported confidence between 0.0 and 1.0. Use below 0.6 when facts are missing or clauses conflict.")
        double confidence,

        @JsonPropertyDescription("One-sentence summary of the recommendation for the adjuster")
        String summary,

        @JsonPropertyDescription("Step-by-step reasoning that references clauses by ID, e.g. 'Per HOME-1.1 ...'")
        String reasoning,

        @JsonPropertyDescription("IDs of every policy clause relied on, exactly as written in the context, e.g. HOME-1.1")
        List<String> citedClauseIds,

        @JsonPropertyDescription("Facts or documents needed before a final decision can be made. Empty if none.")
        List<String> missingInformation,

        @JsonPropertyDescription("Draft message to the customer in plain, polite language. No promises of payment.")
        String customerMessage
) {
    public ClaimDecision {
        citedClauseIds = citedClauseIds == null ? List.of() : List.copyOf(citedClauseIds);
        missingInformation = missingInformation == null ? List.of() : List.copyOf(missingInformation);
    }

    /** Used when retrieval finds nothing relevant: we never ask the model to guess. */
    public static ClaimDecision abstain(String why) {
        return new ClaimDecision(
                Decision.NEEDS_INFO, 0.0,
                "No relevant policy language found. Assistant abstained.",
                why,
                List.of(),
                List.of("Confirm the correct policy type", "Manual coverage review by an adjuster"),
                "Thank you for your claim. A member of our claims team will review it personally and contact you shortly.");
    }
}
