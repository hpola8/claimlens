package com.claimlens.guardrail;

/**
 * Result of one safety check, shown to the reviewer next to the draft.
 *
 * @param key    stable identifier, e.g. "citations"
 * @param label  human-readable name
 * @param status PASS, WARN or FAIL. Any FAIL forces full human review.
 * @param detail one-line explanation of what was found
 */
public record GuardrailCheck(String key, String label, Status status, String detail) {

    public enum Status { PASS, WARN, FAIL, SKIPPED }

    public static GuardrailCheck pass(String key, String label, String detail) {
        return new GuardrailCheck(key, label, Status.PASS, detail);
    }

    public static GuardrailCheck warn(String key, String label, String detail) {
        return new GuardrailCheck(key, label, Status.WARN, detail);
    }

    public static GuardrailCheck fail(String key, String label, String detail) {
        return new GuardrailCheck(key, label, Status.FAIL, detail);
    }

    public static GuardrailCheck skipped(String key, String label, String detail) {
        return new GuardrailCheck(key, label, Status.SKIPPED, detail);
    }
}
