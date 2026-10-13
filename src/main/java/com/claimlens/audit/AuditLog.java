package com.claimlens.audit;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of everything that influenced a decision: what was retrieved, which prompt
 * version ran, what the model said, which checks fired and what the human decided.
 *
 * <p>In-memory for the demo. In production this would be an append-only table or an event stream.
 */
@Component
public class AuditLog {

    public record Entry(Instant at, String claimId, String actor, String event, String detail, Map<String, Object> data) {
    }

    private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

    public void record(String claimId, String actor, String event, String detail, Map<String, Object> data) {
        entries.add(new Entry(Instant.now(), claimId, actor, event, detail, data == null ? Map.of() : Map.copyOf(data)));
    }

    public void record(String claimId, String actor, String event, String detail) {
        record(claimId, actor, event, detail, Map.of());
    }

    public List<Entry> forClaim(String claimId) {
        synchronized (entries) {
            return entries.stream().filter(e -> e.claimId().equals(claimId)).toList();
        }
    }

    public List<Entry> recent(int limit) {
        synchronized (entries) {
            int from = Math.max(0, entries.size() - limit);
            List<Entry> copy = new ArrayList<>(entries.subList(from, entries.size()));
            Collections.reverse(copy);
            return copy;
        }
    }
}
