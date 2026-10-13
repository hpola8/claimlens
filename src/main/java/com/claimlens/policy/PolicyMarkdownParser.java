package com.claimlens.policy;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a policy markdown file into clauses along its "## CLAUSE-ID Title" headings.
 *
 * <p>Splitting on clause boundaries (instead of blindly every N tokens) is what makes
 * citations trustworthy: every chunk in the vector store maps to exactly one clause ID,
 * so the model can only cite IDs that really exist and we can verify them.
 */
public final class PolicyMarkdownParser {

    private PolicyMarkdownParser() {
    }

    public static List<PolicyClause> parse(String markdown) {
        String policyName = "";
        String policyType = "";
        String effectiveDate = "";
        List<PolicyClause> clauses = new ArrayList<>();

        String currentId = null;
        String currentTitle = null;
        StringBuilder body = new StringBuilder();

        for (String raw : markdown.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("## ")) {
                flush(clauses, currentId, currentTitle, body, policyType, policyName, effectiveDate);
                String heading = line.substring(3).strip();
                int space = heading.indexOf(' ');
                currentId = space > 0 ? heading.substring(0, space) : heading;
                currentTitle = space > 0 ? heading.substring(space + 1).strip() : "";
                body.setLength(0);
            } else if (line.startsWith("# ")) {
                policyName = line.substring(2).strip();
            } else if (currentId == null && line.startsWith("policyType:")) {
                policyType = line.substring("policyType:".length()).strip();
            } else if (currentId == null && line.startsWith("effectiveDate:")) {
                effectiveDate = line.substring("effectiveDate:".length()).strip();
            } else if (currentId != null && !line.isEmpty()) {
                if (!body.isEmpty()) {
                    body.append(' ');
                }
                body.append(line);
            }
        }
        flush(clauses, currentId, currentTitle, body, policyType, policyName, effectiveDate);

        if (policyType.isBlank()) {
            throw new IllegalArgumentException("Policy document is missing a 'policyType:' header");
        }
        return List.copyOf(clauses);
    }

    private static void flush(List<PolicyClause> out, String id, String title, StringBuilder body,
                              String type, String name, String effective) {
        if (id != null && !body.isEmpty()) {
            out.add(new PolicyClause(id, title, body.toString(), type, name, effective));
        }
    }
}
