package com.claimlens.policy;

/** One numbered clause from a policy document, e.g. HOME-1.2. */
public record PolicyClause(
        String clauseId,
        String title,
        String text,
        String policyType,
        String policyName,
        String effectiveDate
) {
}
