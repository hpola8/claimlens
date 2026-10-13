package com.claimlens.web;

import com.claimlens.audit.AuditLog;
import com.claimlens.review.ReviewQueue;
import com.claimlens.review.ReviewQueue.ReviewCommand;
import com.claimlens.review.ReviewQueue.ReviewItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewQueue queue;
    private final AuditLog audit;

    public ReviewController(ReviewQueue queue, AuditLog audit) {
        this.queue = queue;
        this.audit = audit;
    }

    @GetMapping("/reviews")
    public List<ReviewItem> reviews() {
        return queue.list();
    }

    @GetMapping("/reviews/{claimId}")
    public ReviewItem review(@PathVariable String claimId) {
        return queue.get(claimId).orElseThrow(() -> new IllegalArgumentException("Unknown claim " + claimId));
    }

    @PostMapping("/reviews/{claimId}")
    public ReviewItem act(@PathVariable String claimId, @RequestBody ReviewCommand command) {
        if (command == null || command.action() == null) {
            throw new IllegalArgumentException("action is required (ACCEPT, EDIT or REJECT)");
        }
        return queue.act(claimId, command);
    }

    @GetMapping("/metrics")
    public ReviewQueue.Metrics metrics() {
        return queue.metrics();
    }

    @GetMapping("/audit")
    public List<AuditLog.Entry> audit(@RequestParam(required = false) String claimId,
                                     @RequestParam(defaultValue = "100") int limit) {
        return claimId == null || claimId.isBlank() ? audit.recent(limit) : audit.forClaim(claimId);
    }
}
