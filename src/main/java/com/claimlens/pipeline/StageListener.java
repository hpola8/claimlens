package com.claimlens.pipeline;

import java.util.Map;

/** Receives live progress so the UI can animate each pipeline stage as it actually happens. */
@FunctionalInterface
public interface StageListener {

    enum Stage { SCREEN, RETRIEVE, DRAFT, VERIFY, ROUTE }

    enum State { RUNNING, DONE, WARN, FAIL }

    void onStage(Stage stage, State state, String message, Map<String, Object> data);

    StageListener NO_OP = (stage, state, message, data) -> { };
}
