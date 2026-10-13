package com.claimlens.web;

import com.claimlens.claim.ClaimRequest;
import com.claimlens.pipeline.ClaimReviewPipeline;
import com.claimlens.pipeline.PipelineResult;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/claims")
public class ClaimController {

    private static final Logger log = LoggerFactory.getLogger(ClaimController.class);

    private final ClaimReviewPipeline pipeline;
    private final ExecutorService executor;

    public ClaimController(ClaimReviewPipeline pipeline, ExecutorService pipelineExecutor) {
        this.pipeline = pipeline;
        this.executor = pipelineExecutor;
    }

    /** Blocking variant, handy for curl and the evaluation suite. */
    @PostMapping("/analyze")
    public PipelineResult analyze(@Valid @RequestBody ClaimRequest claim) {
        return pipeline.analyze(claim);
    }

    /**
     * Streams each pipeline stage as a server-sent event, so the UI shows what the system is
     * really doing (screening, retrieving, drafting, verifying, routing) as it happens.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ClaimRequest claim) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        executor.submit(() -> {
            try {
                PipelineResult result = pipeline.analyze(claim, (stage, state, message, data) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("stage", stage);
                    payload.put("state", state);
                    payload.put("message", message);
                    payload.put("data", data);
                    send(emitter, "stage", payload);
                });
                send(emitter, "result", result);
                emitter.complete();
            } catch (Exception e) {
                log.error("Pipeline failed", e);
                send(emitter, "error", Map.of("message", rootMessage(e)));
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Client went away while streaming '{}'", name);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
