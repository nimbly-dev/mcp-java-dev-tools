package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, transport-neutral execution-orchestration result. */
public record ExecutionOrchestrationResult(
        String status, String reasonCode, String nextAction, Map<String, Object> reasonMeta, Map<String, Object> details) {

    /** Defensively copies public result maps. */
    public ExecutionOrchestrationResult {
        status = status == null ? "blocked" : status;
        reasonCode = reasonCode == null ? "execution_orchestration_internal_error" : reasonCode;
        reasonMeta = copy(reasonMeta);
        details = copy(details);
    }

    /** Creates a deterministic Fail-Closed result. */
    public static ExecutionOrchestrationResult blocked(String reasonCode, String nextAction, Map<String, Object> reasonMeta) {
        return new ExecutionOrchestrationResult("blocked", reasonCode, nextAction, reasonMeta, Map.of());
    }

    private static Map<String, Object> copy(Map<String, Object> input) {
        return input == null || input.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
