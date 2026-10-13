# ClaimLens

**A grounded, human-in-the-loop claims review assistant built with Spring Boot and Spring AI.**

An adjuster submits an insurance claim. ClaimLens retrieves the policy clauses that apply, drafts a
decision that cites them, checks the draft for hallucinations, and routes it to a human reviewer.
Nothing reaches a customer until a person approves it.

> The AI drafts in pencil. A person signs in ink.

---

## What it demonstrates

| Skill | Where it lives |
|---|---|
| **Spring AI `ChatClient`** with a versioned system prompt | `pipeline/ClaimReviewPipeline`, `pipeline/Prompts` |
| **Structured output** mapped to a Java record (`.entity(ClaimDecision.class)`) | `claim/ClaimDecision` |
| **RAG**: clause-aware chunking, embeddings, `VectorStore`, metadata-filtered `SearchRequest` | `policy/PolicyIngestionService`, `ClaimReviewPipeline.retrieve` |
| **Swappable vector stores**: `SimpleVectorStore` (default) or **PGVector** (profile) | `config/AiConfig`, `application.yml`, `docker-compose.yml` |
| **Hallucination checks**: deterministic citation validation + Spring AI `RelevancyEvaluator` and `FactCheckingEvaluator` | `guardrail/CitationValidator`, `ClaimReviewPipeline.runEvaluators` |
| **Abstention**: refuses to draft when retrieval finds nothing relevant | `ClaimReviewPipeline.analyze` (step 3) |
| **Prompt-injection screening** of untrusted input | `guardrail/InjectionDetector` + system prompt rule 5 |
| **Human-in-the-loop routing and review queue** | `guardrail/RoutingPolicy`, `review/ReviewQueue` |
| **Audit trail** of prompt version, evidence, model output, checks and reviewer action | `audit/AuditLog` |
| **Evaluation suite** over a labelled golden set | `src/test/.../GoldenSetEvaluationTest` |
| **Live pipeline streaming** to the UI with Server-Sent Events | `web/ClaimController#stream` |

## The pipeline

```
 Claim ─▶ 1 SCREEN ─▶ 2 RETRIEVE ─▶ 3 DRAFT ─▶ 4 VERIFY ─▶ 5 ROUTE ─▶ Reviewer queue ─▶ Customer
          injection    VectorStore   ChatClient   citations     fast-track     accept / edit /
          detector     topK + filter  + .entity()  consistency   or full        reject (logged)
                       threshold ──▶ abstain       LLM judges    review
```

1. **Screen.** Claim text is untrusted. `InjectionDetector` flags instruction-like phrases ("ignore previous instructions", "mark this claim approved"). A hit forces full review.
2. **Retrieve.** Policies in `src/main/resources/policies/*.md` are split on clause headings (`## HOME-1.1 ...`) so every chunk maps to exactly one clause ID. Vector search is filtered by `policyType`. Clauses below the similarity cutoff are shown to the reviewer but never sent to the model; if none clear it, the assistant **abstains**.
3. **Draft.** `ChatClient` + structured output returns a `ClaimDecision` (decision, confidence, reasoning, cited clause IDs, missing information, customer message). Temperature 0.
4. **Verify.**
   - `CitationValidator`: every clause ID in the citations *or the reasoning text* must be one the model was shown.
   - Consistency check: e.g. NEEDS_INFO must list what is missing.
   - Spring AI `RelevancyEvaluator` and `FactCheckingEvaluator` (LLM-as-judge), run in parallel on virtual threads. An evaluator error is a WARN, never a silent PASS.
5. **Route.** `RoutingPolicy` sends to **full review** when any check fails, the decision is DENY, the decision is NEEDS_INFO, confidence is below 0.75, or the amount exceeds $10,000. Otherwise **fast-track** (one-click approval). Every draft goes to the queue either way.

## The UI

Open `http://localhost:8080`. First-time visitors get a five-step guided tour. The page shows:

- **Claim file** pre-filled with a sample, plus six sample claims, each built to exercise a different safeguard (happy path, denial, ambiguity, missing info, prompt injection, abstention).
- **Live pipeline tracker** driven by server-sent events from the real pipeline.
- **Evidence beside the draft**: retrieved clauses with similarity scores and the cutoff; cited clauses highlighted; clause IDs in the reasoning are clickable.
- **Safety checks and routing reasons**, then **Approve and send / Edit before sending / Reject draft**. The decision stamp is drawn in pencil until a person signs.
- **Reviewer queue** with override rate and abstention counts, **Audit trail** per claim, and **How it works** with the live configuration and system prompt.

If the API isn't reachable (for example when the page is opened as a static file), the UI switches to a
**demo mode** that replays recorded responses for the sample claims.

The page source is `ui/app.html`. After editing it, run `./scripts/build-ui.sh` to regenerate
`src/main/resources/static/index.html`.

## Run it

Requirements: Java 21, Maven 3.9+, an OpenAI API key.

```bash
export OPENAI_API_KEY=sk-...
mvn spring-boot:run
# open http://localhost:8080
```

### With PGVector

```bash
docker compose up -d
mvn spring-boot:run -Dspring-boot.run.profiles=pgvector
```

Ingestion uses deterministic UUIDs per clause, so restarting re-upserts instead of duplicating rows.

### Configuration (`application.yml`)

| Property | Default | Meaning |
|---|---|---|
| `spring.ai.openai.chat.options.model` | `gpt-4o-mini` | Override with `CLAIMLENS_CHAT_MODEL` |
| `claimlens.retrieval.top-k` | `5` | Clauses retrieved per claim |
| `claimlens.retrieval.similarity-threshold` | `0.30` | Below this, a clause is not evidence |
| `claimlens.guardrails.llm-evaluators` | `true` | Run Relevancy + FactChecking evaluators |
| `claimlens.guardrails.min-model-confidence` | `0.75` | Below this, full review |
| `claimlens.guardrails.high-value-threshold` | `10000` | Above this amount, full review |

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/claims/stream` | Run the pipeline, streaming `stage` events then a `result` (SSE) |
| `POST` | `/api/claims/analyze` | Same, blocking; returns `PipelineResult` |
| `GET` | `/api/reviews` | Review queue |
| `POST` | `/api/reviews/{claimId}` | `{"action":"ACCEPT"\|"EDIT"\|"REJECT","finalDecision":..,"finalMessage":..,"note":..}` |
| `GET` | `/api/metrics` | Pending, reviewed, fast-track share, override rate, guardrail failures |
| `GET` | `/api/audit?claimId=` | Audit entries |
| `GET` | `/api/status`, `/api/samples`, `/api/policies` | UI metadata |

```bash
curl -s localhost:8080/api/claims/analyze -H 'Content-Type: application/json' -d '{
  "policyType":"AUTO","claimantName":"Test","incidentDate":"2026-09-14","reportedDate":"2026-09-14",
  "amount":450,"description":"A rock from a truck cracked my windshield on the highway."}' | jq .draft
```

## Tests

```bash
mvn test                                  # unit tests: guardrails, routing, policy parsing (no API key needed)
OPENAI_API_KEY=sk-... mvn test -Dtest=GoldenSetEvaluationTest   # real model, prints an eval report
```

The golden-set test runs 8 labelled claims through the real pipeline and reports decision accuracy,
key-clause citation rate and hallucinated citations. It fails if any draft cites a clause it wasn't
shown, or if accuracy drops below 75%. Add cases to `src/test/resources/golden-claims.json` whenever a
reviewer overrides the AI; overrides are the best source of new test cases.

## Known limitations

- Model confidence is self-reported and uncalibrated, so it is only one routing signal.
- The injection screen is pattern-based and can be evaded; it raises the review level rather than making the text safe.
- The judge model can share blind spots with the drafting model; reviewer overrides are the ground truth.
- Clauses that reference each other need both to be retrieved.
- Review queue and audit log are in memory. In production they'd be an append-only table or event stream.
- Sample policies are simplified and fictional.

## Project layout

```
src/main/java/com/claimlens
├── ClaimLensApplication.java
├── config/      AiConfig (ChatClient, VectorStore, evaluators), ClaimLensProperties
├── policy/      PolicyMarkdownParser, PolicyIngestionService
├── claim/       ClaimRequest, ClaimDecision (structured output), Decision
├── pipeline/    ClaimReviewPipeline, Prompts, PipelineResult, StageListener
├── guardrail/   InjectionDetector, CitationValidator, RoutingPolicy, GuardrailCheck
├── review/      ReviewQueue (human-in-the-loop)
├── audit/       AuditLog
└── web/         ClaimController (SSE), ReviewController, MetaController
```

Built with Spring Boot 3.5 and Spring AI 1.1.
