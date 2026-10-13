package com.claimlens.guardrail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic hallucination check: every clause the model cites must be one we actually
 * retrieved and showed it. Also catches clause IDs mentioned in the reasoning text that were
 * left out of the citation list (or invented outright).
 */
public final class CitationValidator {

    public static final String KEY = "citations";
    private static final String LABEL = "Citations are real";
    private static final Pattern CLAUSE_ID = Pattern.compile("\\b(?:HOME|AUTO|DEV)-\\d+\\.\\d+\\b");

    public GuardrailCheck validate(Collection<String> citedIds, String reasoning, Collection<String> retrievedIds) {
        Set<String> retrieved = new LinkedHashSet<>(retrievedIds);
        Set<String> cited = new LinkedHashSet<>(citedIds);

        // IDs that appear in the reasoning text but were not listed as citations still count.
        Matcher m = CLAUSE_ID.matcher(reasoning == null ? "" : reasoning);
        while (m.find()) {
            cited.add(m.group());
        }

        if (cited.isEmpty()) {
            return GuardrailCheck.fail(KEY, LABEL, "The draft cites no policy clauses, so it can't be verified.");
        }

        List<String> fabricated = new ArrayList<>();
        for (String id : cited) {
            if (!retrieved.contains(id)) {
                fabricated.add(id);
            }
        }
        if (!fabricated.isEmpty()) {
            return GuardrailCheck.fail(KEY, LABEL,
                    "Cites clauses that were not in the retrieved context: " + String.join(", ", fabricated));
        }
        return GuardrailCheck.pass(KEY, LABEL,
                "All " + cited.size() + " cited clause(s) exist in the retrieved policy text.");
    }
}
