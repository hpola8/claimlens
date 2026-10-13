package com.claimlens.web;

import com.claimlens.pipeline.ClaimReviewPipeline;
import com.claimlens.policy.PolicyClause;
import com.claimlens.policy.PolicyIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Read-only endpoints the UI uses to explain itself: status, sample claims and the policy library. */
@RestController
@RequestMapping("/api")
public class MetaController {

    private final PolicyIngestionService ingestion;
    private final ClaimReviewPipeline pipeline;
    private final JsonNode samples;

    public MetaController(PolicyIngestionService ingestion, ClaimReviewPipeline pipeline, ObjectMapper mapper)
            throws IOException {
        this.ingestion = ingestion;
        this.pipeline = pipeline;
        try (InputStream in = new ClassPathResource("samples/sample-claims.json").getInputStream()) {
            this.samples = mapper.readTree(in);
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("knowledgeBase", ingestion.status());
        m.put("pipeline", pipeline.describe());
        return m;
    }

    @GetMapping("/samples")
    public JsonNode samples() {
        return samples;
    }

    @GetMapping("/policies")
    public Map<String, List<PolicyClause>> policies() {
        return ingestion.catalog().stream()
                .collect(Collectors.groupingBy(PolicyClause::policyType, LinkedHashMap::new, Collectors.toList()));
    }
}
