package com.claimlens.pipeline;

import com.claimlens.audit.AuditLog;
import com.claimlens.claim.ClaimDecision;
import com.claimlens.claim.ClaimRequest;
import com.claimlens.claim.Decision;
import com.claimlens.config.ClaimLensProperties;
import com.claimlens.guardrail.CitationValidator;
import com.claimlens.guardrail.GuardrailCheck;
import com.claimlens.guardrail.InjectionDetector;
import com.claimlens.guardrail.RoutingPolicy;
import com.claimlens.pipeline.PipelineResult.RetrievedClause;
import com.claimlens.pipeline.StageListener.Stage;
import com.claimlens.pipeline.StageListener.State;
import com.claimlens.review.ReviewQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * The full review pipeline:
 * <pre>
 *  SCREEN   -> look for prompt-injection in the untrusted claim text
 *  RETRIEVE -> vector search over policy clauses, filtered to the claim's policy type
 *  DRAFT    -> ChatClient + structured output (ClaimDecision), grounded only in retrieved clauses
 *  VERIFY   -> citation check, consistency check, Spring AI Relevancy + FactChecking evaluators
 *  ROUTE    -> fast-track vs full human review; everything lands in the review queue
 * </pre>
 */
@Service
public class ClaimReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ClaimReviewPipeline.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RelevancyEvaluator relevancyEvaluator;
    private final FactCheckingEvaluator factCheckingEvaluator;
    private final CitationValidator citationValidator;
    private final InjectionDetector injectionDetector;
    private final RoutingPolicy routingPolicy;
    private final ReviewQueue reviewQueue;
    private final AuditLog audit;
    private final ClaimLensProperties props;
    private final ExecutorService executor;
    private final String modelName;

    public ClaimReviewPipeline(ChatClient chatClient, VectorStore vectorStore,
                               RelevancyEvaluator relevancyEvaluator, FactCheckingEvaluator factCheckingEvaluator,
                               CitationValidator citationValidator, InjectionDetector injectionDetector,
                               RoutingPolicy routingPolicy, ReviewQueue reviewQueue, AuditLog audit,
                               ClaimLensProperties props, ExecutorService pipelineExecutor,
                               @Value("${spring.ai.openai.chat.options.model:unknown}") String modelName) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.relevancyEvaluator = relevancyEvaluator;
        this.factCheckingEvaluator = factCheckingEvaluator;
        this.citationValidator = citationValidator;
        this.injectionDetector = injectionDetector;
        this.routingPolicy = routingPolicy;
        this.reviewQueue = reviewQueue;
        this.audit = audit;
        this.props = props;
        this.executor = pipelineExecutor;
        this.modelName = modelName;
    }

    public PipelineResult analyze(ClaimRequest claim) {
        return analyze(claim, StageListener.NO_OP);
    }

    public PipelineResult analyze(ClaimRequest claim, StageListener listener) {
        long start = System.currentTimeMillis();
        String claimId = newClaimId();
        List<GuardrailCheck> checks = new ArrayList<>();
        audit.record(claimId, "adjuster", "SUBMITTED",
                claim.policyType() + " claim for $" + claim.amount().toPlainString() + " by " + claim.claimantName());

        // 1. SCREEN ---------------------------------------------------------------------------
        listener.onStage(Stage.SCREEN, State.RUNNING, "Scanning claim text for hidden instructions", Map.of());
        GuardrailCheck injection = injectionDetector.screen(claim.description());
        checks.add(injection);
        audit.record(claimId, "system", "INPUT_SCREENED", injection.detail());
        listener.onStage(Stage.SCREEN, injection.status() == GuardrailCheck.Status.FAIL ? State.WARN : State.DONE,
                injection.detail(), Map.of("check", injection));

        // 2. RETRIEVE -------------------------------------------------------------------------
        listener.onStage(Stage.RETRIEVE, State.RUNNING, "Searching " + claim.policyType() + " policy clauses", Map.of());
        List<RetrievedClause> retrieved = retrieve(claim);
        List<RetrievedClause> evidence = retrieved.stream().filter(RetrievedClause::usedAsEvidence).toList();
        double topScore = retrieved.isEmpty() ? 0.0 : retrieved.getFirst().score();
        audit.record(claimId, "system", "RETRIEVED",
                evidence.size() + " clause(s) above threshold " + props.retrieval().similarityThreshold(),
                Map.of("clauses", evidence.stream().map(RetrievedClause::clauseId).toList(), "topScore", topScore));
        boolean abstain = evidence.isEmpty();
        listener.onStage(Stage.RETRIEVE, abstain ? State.WARN : State.DONE,
                abstain ? String.format("Best match scored %.2f, below the %.2f threshold", topScore,
                        props.retrieval().similarityThreshold())
                        : evidence.size() + " relevant clause(s) found",
                Map.of("retrieved", retrieved));

        // 3. DRAFT ----------------------------------------------------------------------------
        ClaimDecision draft;
        if (abstain) {
            draft = ClaimDecision.abstain(String.format(
                    "No policy clause scored above the relevance threshold (best %.2f). Rather than let the model "
                            + "answer from general knowledge, the assistant abstained.", topScore));
            checks.add(GuardrailCheck.fail("grounding", "Grounded in policy text",
                    "Nothing relevant was retrieved, so no draft was generated."));
            audit.record(claimId, "system", "ABSTAINED", draft.reasoning());
            listener.onStage(Stage.DRAFT, State.WARN, "Abstained: not enough policy evidence to draft a decision",
                    Map.of("draft", draft));
        } else {
            listener.onStage(Stage.DRAFT, State.RUNNING, "Drafting a decision from " + evidence.size() + " clauses", Map.of());
            draft = draft(claim, evidence);
            audit.record(claimId, "model:" + modelName, "DRAFTED",
                    draft.decision() + " (confidence " + String.format("%.2f", draft.confidence()) + ")",
                    Map.of("promptVersion", Prompts.VERSION, "cited", draft.citedClauseIds()));
            listener.onStage(Stage.DRAFT, State.DONE, "Draft: " + draft.decision(), Map.of("draft", draft));
        }

        // 4. VERIFY ---------------------------------------------------------------------------
        listener.onStage(Stage.VERIFY, State.RUNNING, "Checking the draft for hallucinations", Map.of());
        if (!abstain) {
            checks.add(citationValidator.validate(draft.citedClauseIds(), draft.reasoning(),
                    retrieved.stream().filter(RetrievedClause::usedAsEvidence).map(RetrievedClause::clauseId).toList()));
            checks.add(consistencyCheck(draft));
            checks.addAll(runEvaluators(claim, evidence, draft));
        } else {
            checks.add(GuardrailCheck.skipped("citations", "Citations are real", "No draft to check."));
        }
        boolean anyFail = checks.stream().anyMatch(c -> c.status() == GuardrailCheck.Status.FAIL);
        checks.forEach(c -> audit.record(claimId, "system", "CHECK_" + c.status(), c.label() + ": " + c.detail()));
        listener.onStage(Stage.VERIFY, anyFail ? State.WARN : State.DONE,
                anyFail ? "Some checks need a human's attention" : "All checks passed", Map.of("checks", checks));

        // 5. ROUTE ----------------------------------------------------------------------------
        RoutingPolicy.Result routing = routingPolicy.route(draft.decision(), draft.confidence(), claim.amount(), checks);
        audit.record(claimId, "system", "ROUTED", routing.route() + ": " + String.join("; ", routing.reasons()));

        PipelineResult result = new PipelineResult(claimId, Instant.now(), claim, retrieved, draft, abstain,
                List.copyOf(checks), routing.route(), routing.reasons(), Prompts.VERSION, modelName,
                System.currentTimeMillis() - start);
        reviewQueue.submit(result);
        listener.onStage(Stage.ROUTE, routing.route() == RoutingPolicy.Route.FAST_TRACK ? State.DONE : State.WARN,
                routing.route() == RoutingPolicy.Route.FAST_TRACK ? "Fast-track: one-click approval"
                        : "Full human review required",
                Map.of("route", routing.route(), "reasons", routing.reasons()));
        return result;
    }

    private List<RetrievedClause> retrieve(ClaimRequest claim) {
        // Search with threshold 0 so the UI can show near-misses; we apply the threshold ourselves.
        SearchRequest request = SearchRequest.builder()
                .query(claim.description())
                .topK(props.retrieval().topK())
                .similarityThresholdAll()
                .filterExpression("policyType == '" + claim.policyType() + "'")
                .build();
        double threshold = props.retrieval().similarityThreshold();
        List<RetrievedClause> out = new ArrayList<>();
        for (Document d : vectorStore.similaritySearch(request)) {
            double score = d.getScore() == null ? 0.0 : d.getScore();
            String clauseId = String.valueOf(d.getMetadata().get("clauseId"));
            String title = String.valueOf(d.getMetadata().getOrDefault("title", ""));
            String text = d.getText() == null ? "" : d.getText();
            // Strip the "ID Title" header line we prepended at ingestion time.
            int nl = text.indexOf('\n');
            String body = nl > 0 && text.startsWith(clauseId) ? text.substring(nl + 1) : text;
            out.add(new RetrievedClause(clauseId, title, body, score, score >= threshold));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out;
    }

    private ClaimDecision draft(ClaimRequest claim, List<RetrievedClause> evidence) {
        ClaimDecision d = chatClient.prompt()
                .system(Prompts.SYSTEM)
                .user(Prompts.user(claim, evidence))
                .call()
                .entity(ClaimDecision.class);
        if (d == null || d.decision() == null) {
            throw new IllegalStateException("Model returned no usable decision");
        }
        double conf = Math.max(0.0, Math.min(1.0, d.confidence()));
        return new ClaimDecision(d.decision(), conf, d.summary(), d.reasoning(), d.citedClauseIds(),
                d.missingInformation(), d.customerMessage());
    }

    /** Cheap deterministic sanity check on the structured output. */
    private GuardrailCheck consistencyCheck(ClaimDecision d) {
        String key = "consistency";
        String label = "Draft is internally consistent";
        if (d.decision() == Decision.NEEDS_INFO && d.missingInformation().isEmpty()) {
            return GuardrailCheck.warn(key, label, "Asks for more information but doesn't say what is missing.");
        }
        if (d.decision() == Decision.APPROVE && !d.missingInformation().isEmpty()) {
            return GuardrailCheck.warn(key, label, "Recommends approval while listing missing information.");
        }
        if (d.decision() != Decision.NEEDS_INFO && d.confidence() < 0.3) {
            return GuardrailCheck.warn(key, label, "Gives a firm decision with very low confidence.");
        }
        return GuardrailCheck.pass(key, label, "Decision, confidence and missing-information list agree.");
    }

    /** Runs Spring AI's LLM-as-judge evaluators in parallel. A failure to evaluate is a WARN, not a PASS. */
    private List<GuardrailCheck> runEvaluators(ClaimRequest claim, List<RetrievedClause> evidence, ClaimDecision d) {
        if (!props.guardrails().llmEvaluators()) {
            return List.of(
                    GuardrailCheck.skipped("relevancy", "Answers the actual claim", "LLM evaluators disabled"),
                    GuardrailCheck.skipped("factcheck", "Supported by policy text", "LLM evaluators disabled"));
        }
        List<Document> docs = evidence.stream()
                .map(c -> new Document("[" + c.clauseId() + "] " + c.title() + "\n" + c.text()))
                .toList();
        String answer = d.summary() + "\n" + d.reasoning();
        EvaluationRequest req = new EvaluationRequest(claim.description(), docs, answer);

        CompletableFuture<GuardrailCheck> relevancy = CompletableFuture.supplyAsync(() -> evaluate(
                relevancyEvaluator, req, "relevancy", "Answers the actual claim",
                "Judge model confirms the draft addresses this claim using the context.",
                "Judge model says the draft doesn't address the claim with the given context."), executor);
        CompletableFuture<GuardrailCheck> facts = CompletableFuture.supplyAsync(() -> evaluate(
                factCheckingEvaluator, req, "factcheck", "Supported by policy text",
                "Judge model found the reasoning supported by the retrieved clauses.",
                "Judge model found statements not supported by the retrieved clauses."), executor);
        return List.of(relevancy.join(), facts.join());
    }

    private GuardrailCheck evaluate(Evaluator evaluator, EvaluationRequest req, String key, String label,
                                    String passMsg, String failMsg) {
        try {
            EvaluationResponse r = evaluator.evaluate(req);
            return r.isPass() ? GuardrailCheck.pass(key, label, passMsg) : GuardrailCheck.fail(key, label, failMsg);
        } catch (Exception e) {
            log.warn("Evaluator {} failed", key, e);
            return GuardrailCheck.warn(key, label, "Evaluator unavailable: " + e.getClass().getSimpleName());
        }
    }

    private static String newClaimId() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder("CLM-");
        for (int i = 0; i < 6; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Exposed for the UI's "how it works" panel. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promptVersion", Prompts.VERSION);
        m.put("model", modelName);
        m.put("vectorStore", vectorStore.getName());
        m.put("topK", props.retrieval().topK());
        m.put("similarityThreshold", props.retrieval().similarityThreshold());
        m.put("llmEvaluators", props.guardrails().llmEvaluators());
        m.put("minModelConfidence", props.guardrails().minModelConfidence());
        m.put("highValueThreshold", props.guardrails().highValueThreshold());
        m.put("systemPrompt", Prompts.SYSTEM);
        return m;
    }
}
