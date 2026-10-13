package com.claimlens.eval;

import com.claimlens.claim.ClaimRequest;
import com.claimlens.guardrail.GuardrailCheck;
import com.claimlens.pipeline.ClaimReviewPipeline;
import com.claimlens.pipeline.PipelineResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real pipeline (real model, real embeddings) against a labelled set of claims and reports:
 * <ul>
 *   <li>decision accuracy: did the draft match the expected outcome?</li>
 *   <li>citation recall: did it cite the clause that decides the case?</li>
 *   <li>hallucinated citations: did it ever cite a clause it wasn't shown?</li>
 * </ul>
 * Costs real API calls, so it only runs when OPENAI_API_KEY is set:
 * {@code mvn test -Dtest=GoldenSetEvaluationTest}
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class GoldenSetEvaluationTest {

    @Autowired
    ClaimReviewPipeline pipeline;
    @Autowired
    ObjectMapper mapper;

    @Test
    void goldenSet() throws Exception {
        JsonNode cases;
        try (InputStream in = new ClassPathResource("golden-claims.json").getInputStream()) {
            cases = mapper.readTree(in);
        }

        int correct = 0, citedKeyClause = 0, hallucinated = 0;
        List<String> report = new ArrayList<>();

        for (JsonNode c : cases) {
            ClaimRequest claim = mapper.treeToValue(c.get("claim"), ClaimRequest.class);
            PipelineResult r = pipeline.analyze(claim);

            List<String> expected = Arrays.asList(c.get("expectedDecision").asText().split("\\|"));
            boolean decisionOk = expected.contains(r.draft().decision().name());
            List<String> mustCite = mapper.convertValue(c.get("mustCite"), mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
            boolean citeOk = r.draft().citedClauseIds().containsAll(mustCite);
            boolean fabricated = r.checks().stream()
                    .anyMatch(k -> k.key().equals("citations") && k.status() == GuardrailCheck.Status.FAIL);

            if (decisionOk) correct++;
            if (citeOk) citedKeyClause++;
            if (fabricated) hallucinated++;
            report.add(String.format("%-55s expected=%-16s got=%-10s cited=%s %s",
                    c.get("name").asText(), String.join("|", expected), r.draft().decision(),
                    r.draft().citedClauseIds(), fabricated ? "<< HALLUCINATED CITATION" : ""));
        }

        int n = cases.size();
        System.out.println("\n===== ClaimLens golden-set evaluation =====");
        report.forEach(System.out::println);
        System.out.printf("Decision accuracy:       %d/%d (%.0f%%)%n", correct, n, 100.0 * correct / n);
        System.out.printf("Key-clause citation rate: %d/%d (%.0f%%)%n", citedKeyClause, n, 100.0 * citedKeyClause / n);
        System.out.printf("Hallucinated citations:  %d/%d%n%n", hallucinated, n);

        assertThat(hallucinated).as("drafts citing clauses they were never shown").isZero();
        assertThat((double) correct / n).as("decision accuracy").isGreaterThanOrEqualTo(0.75);
    }
}
