package com.claimlens.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();
    private final List<String> retrieved = List.of("HOME-1.1", "HOME-1.2", "HOME-4.1");

    @Test
    void passesWhenEveryCitationWasRetrieved() {
        var check = validator.validate(List.of("HOME-1.1", "HOME-4.1"), "Per HOME-1.1 the burst is covered.", retrieved);
        assertThat(check.status()).isEqualTo(GuardrailCheck.Status.PASS);
    }

    @Test
    void failsOnFabricatedClauseId() {
        var check = validator.validate(List.of("HOME-1.1", "HOME-9.9"), "See HOME-9.9.", retrieved);
        assertThat(check.status()).isEqualTo(GuardrailCheck.Status.FAIL);
        assertThat(check.detail()).contains("HOME-9.9");
    }

    @Test
    void catchesClauseMentionedInReasoningButNotRetrieved() {
        var check = validator.validate(List.of("HOME-1.1"), "Covered under HOME-1.1, but HOME-5.1 limits mold.", retrieved);
        assertThat(check.status()).isEqualTo(GuardrailCheck.Status.FAIL);
        assertThat(check.detail()).contains("HOME-5.1");
    }

    @Test
    void failsWhenNothingIsCited() {
        var check = validator.validate(List.of(), "Looks covered to me.", retrieved);
        assertThat(check.status()).isEqualTo(GuardrailCheck.Status.FAIL);
    }
}
