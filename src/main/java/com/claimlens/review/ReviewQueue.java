package com.claimlens.review;

import com.claimlens.audit.AuditLog;
import com.claimlens.claim.Decision;
import com.claimlens.guardrail.GuardrailCheck;
import com.claimlens.guardrail.RoutingPolicy;
import com.claimlens.pipeline.PipelineResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Human-in-the-loop queue. Every AI draft lands here; nothing reaches a customer until a person acts.
 * Reviewer overrides are recorded because they are the most valuable evaluation data the system has.
 */
@Component
public class ReviewQueue {

    public enum Status { PENDING, ACCEPTED, EDITED, REJECTED }

    public enum Action { ACCEPT, EDIT, REJECT }

    public record ReviewItem(
            PipelineResult result,
            Status status,
            Decision finalDecision,
            String finalMessage,
            String reviewer,
            String reviewerNote,
            Instant reviewedAt,
            boolean overrodeAi
    ) {
    }

    public record ReviewCommand(Action action, Decision finalDecision, String finalMessage, String reviewer, String note) {
    }

    public record Metrics(
            int total, int pending, int reviewed, int fastTrack, int fullReview, int abstentions,
            double overrideRate, Map<String, Integer> guardrailFailures
    ) {
    }

    private final Map<String, ReviewItem> items = new ConcurrentHashMap<>();
    private final AuditLog audit;

    public ReviewQueue(AuditLog audit) {
        this.audit = audit;
    }

    public void submit(PipelineResult result) {
        items.put(result.claimId(), new ReviewItem(result, Status.PENDING, null, null, null, null, null, false));
        audit.record(result.claimId(), "system", "QUEUED",
                "Draft queued for " + (result.route() == RoutingPolicy.Route.FAST_TRACK ? "fast-track" : "full") + " review");
    }

    public List<ReviewItem> list() {
        return items.values().stream()
                .sorted(Comparator.comparing((ReviewItem i) -> i.status() != Status.PENDING)
                        .thenComparing(i -> i.result().createdAt(), Comparator.reverseOrder()))
                .toList();
    }

    public Optional<ReviewItem> get(String claimId) {
        return Optional.ofNullable(items.get(claimId));
    }

    public ReviewItem act(String claimId, ReviewCommand cmd) {
        ReviewItem current = items.get(claimId);
        if (current == null) {
            throw new IllegalArgumentException("Unknown claim " + claimId);
        }
        if (current.status() != Status.PENDING) {
            throw new IllegalStateException("Claim " + claimId + " was already reviewed");
        }
        Decision aiDecision = current.result().draft().decision();
        String reviewer = cmd.reviewer() == null || cmd.reviewer().isBlank() ? "adjuster" : cmd.reviewer();

        ReviewItem updated = switch (cmd.action()) {
            case ACCEPT -> new ReviewItem(current.result(), Status.ACCEPTED, aiDecision,
                    current.result().draft().customerMessage(), reviewer, cmd.note(), Instant.now(), false);
            case EDIT -> {
                Decision fin = cmd.finalDecision() == null ? aiDecision : cmd.finalDecision();
                String msg = cmd.finalMessage() == null || cmd.finalMessage().isBlank()
                        ? current.result().draft().customerMessage() : cmd.finalMessage();
                yield new ReviewItem(current.result(), Status.EDITED, fin, msg, reviewer, cmd.note(),
                        Instant.now(), fin != aiDecision);
            }
            case REJECT -> new ReviewItem(current.result(), Status.REJECTED, null, null, reviewer, cmd.note(),
                    Instant.now(), true);
        };
        items.put(claimId, updated);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("aiDecision", aiDecision.name());
        data.put("finalDecision", updated.finalDecision() == null ? "none" : updated.finalDecision().name());
        data.put("overrodeAi", updated.overrodeAi());
        audit.record(claimId, reviewer, "REVIEW_" + cmd.action().name(),
                cmd.note() == null || cmd.note().isBlank() ? "No note" : cmd.note(), data);
        return updated;
    }

    public Metrics metrics() {
        List<ReviewItem> all = List.copyOf(items.values());
        int reviewed = (int) all.stream().filter(i -> i.status() != Status.PENDING).count();
        int overrides = (int) all.stream().filter(ReviewItem::overrodeAi).count();
        Map<String, Integer> failures = new LinkedHashMap<>();
        for (ReviewItem i : all) {
            for (GuardrailCheck c : i.result().checks()) {
                if (c.status() == GuardrailCheck.Status.FAIL) {
                    failures.merge(c.label(), 1, Integer::sum);
                }
            }
        }
        return new Metrics(
                all.size(),
                all.size() - reviewed,
                reviewed,
                (int) all.stream().filter(i -> i.result().route() == RoutingPolicy.Route.FAST_TRACK).count(),
                (int) all.stream().filter(i -> i.result().route() == RoutingPolicy.Route.FULL_REVIEW).count(),
                (int) all.stream().filter(i -> i.result().abstained()).count(),
                reviewed == 0 ? 0.0 : (double) overrides / reviewed,
                failures);
    }
}
