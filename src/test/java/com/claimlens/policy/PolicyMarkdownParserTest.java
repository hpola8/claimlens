package com.claimlens.policy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyMarkdownParserTest {

    @Test
    void splitsHomePolicyIntoClauses() throws Exception {
        String md = new ClassPathResource("policies/home-ho3.md").getContentAsString(StandardCharsets.UTF_8);
        List<PolicyClause> clauses = PolicyMarkdownParser.parse(md);

        assertThat(clauses).extracting(PolicyClause::clauseId)
                .contains("HOME-1.1", "HOME-1.2", "HOME-4.2", "HOME-6.2");
        assertThat(clauses).allMatch(c -> c.policyType().equals("HOME"));
        assertThat(clauses.getFirst().title()).startsWith("Covered Peril");
        assertThat(clauses.getFirst().text()).contains("burst pipe");
    }

    @Test
    void rejectsDocumentWithoutPolicyType() {
        assertThatThrownBy(() -> PolicyMarkdownParser.parse("# Untyped\n## X-1.1 Thing\nBody"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
