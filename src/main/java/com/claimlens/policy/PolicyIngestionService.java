package com.claimlens.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Loads policy documents from {@code classpath:policies/*.md}, splits them on clause boundaries,
 * embeds them and writes them to the configured Spring AI {@link VectorStore}.
 */
@Service
public class PolicyIngestionService {

    private static final Logger log = LoggerFactory.getLogger(PolicyIngestionService.class);
    /** Clauses longer than this (in tokens) are further split; most clauses are far shorter. */
    private static final int MAX_CLAUSE_TOKENS = 600;

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resources;
    private final Map<String, PolicyClause> catalog = new LinkedHashMap<>();
    private volatile Status status = new Status(false, 0, 0, null);

    public record Status(boolean ready, int policies, int clauses, String error) {
    }

    public PolicyIngestionService(VectorStore vectorStore, ResourcePatternResolver resources) {
        this.vectorStore = vectorStore;
        this.resources = resources;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        try {
            List<PolicyClause> clauses = loadClauses();
            List<Document> documents = toDocuments(clauses);
            vectorStore.add(documents);
            long policies = clauses.stream().map(PolicyClause::policyType).distinct().count();
            status = new Status(true, (int) policies, clauses.size(), null);
            log.info("Indexed {} clauses ({} chunks) from {} policies into {}",
                    clauses.size(), documents.size(), policies, vectorStore.getName());
        } catch (Exception e) {
            status = new Status(false, 0, 0, e.getMessage());
            log.error("Policy ingestion failed. Check OPENAI_API_KEY and vector store settings.", e);
        }
    }

    List<PolicyClause> loadClauses() throws IOException {
        List<PolicyClause> all = new ArrayList<>();
        for (Resource r : resources.getResources("classpath:policies/*.md")) {
            String md = r.getContentAsString(StandardCharsets.UTF_8);
            all.addAll(PolicyMarkdownParser.parse(md));
        }
        synchronized (catalog) {
            catalog.clear();
            all.forEach(c -> catalog.put(c.clauseId(), c));
        }
        return all;
    }

    private List<Document> toDocuments(List<PolicyClause> clauses) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(MAX_CLAUSE_TOKENS)
                .withMinChunkSizeChars(200)
                .build();

        List<Document> docs = new ArrayList<>();
        for (PolicyClause c : clauses) {
            Map<String, Object> metadata = Map.of(
                    "clauseId", c.clauseId(),
                    "title", c.title(),
                    "policyType", c.policyType(),
                    "policyName", c.policyName(),
                    "effectiveDate", c.effectiveDate(),
                    "source", "claimlens-policy");
            // Deterministic UUID per clause: re-running ingestion upserts instead of duplicating (PGVector).
            String id = UUID.nameUUIDFromBytes(c.clauseId().getBytes(StandardCharsets.UTF_8)).toString();
            Document doc = new Document(id, c.clauseId() + " " + c.title() + "\n" + c.text(), metadata);
            // Splitting copies metadata onto each chunk, so every chunk still knows its clause ID.
            List<Document> chunks = splitter.apply(List.of(doc));
            docs.addAll(chunks.size() > 1 ? chunks : List.of(doc));
        }
        return docs;
    }

    public Status status() {
        return status;
    }

    public Collection<PolicyClause> catalog() {
        synchronized (catalog) {
            return List.copyOf(catalog.values());
        }
    }

    public Optional<PolicyClause> clause(String clauseId) {
        synchronized (catalog) {
            return Optional.ofNullable(catalog.get(clauseId));
        }
    }
}
