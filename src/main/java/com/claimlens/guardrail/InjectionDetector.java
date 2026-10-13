package com.claimlens.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cheap, explainable input screen for prompt-injection attempts hidden in claim text.
 *
 * <p>This is a first line of defence, not a guarantee: the system prompt also tells the model
 * to treat claim text as data, and any hit forces human review regardless of the draft.
 */
public final class InjectionDetector {

    public static final String KEY = "injection";
    private static final String LABEL = "No instructions hidden in claim";

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("ignore (all |any )?(previous|prior|above|earlier) (instructions|rules|text|policy)"),
            Pattern.compile("disregard (the |all )?(policy|instructions|rules)"),
            Pattern.compile("\\b(system|developer) (note|prompt|message|instruction)"),
            Pattern.compile("\\b(you are|act as) (now )?(an? )?(ai|assistant|model|reviewer)"),
            Pattern.compile("(mark|set|classify) (this|the) claim (as )?(approve|approved)"),
            Pattern.compile("confidence (of |to |=)?\\s*1(\\.0+)?\\b"),
            Pattern.compile("do not mention")
    );

    public GuardrailCheck screen(String claimText) {
        String text = claimText == null ? "" : claimText.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (Pattern p : PATTERNS) {
            var m = p.matcher(text);
            if (m.find()) {
                hits.add("\"" + m.group().strip() + "\"");
            }
        }
        if (hits.isEmpty()) {
            return GuardrailCheck.pass(KEY, LABEL, "No instruction-like phrases found in the claim text.");
        }
        return GuardrailCheck.fail(KEY, LABEL,
                "Claim text contains instruction-like phrases: " + String.join(", ", hits));
    }
}
