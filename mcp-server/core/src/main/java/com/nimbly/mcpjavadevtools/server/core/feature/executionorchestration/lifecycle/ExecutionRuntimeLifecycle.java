package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Coordinates an owned dynamic runtime without exposing application transport concerns to Core. */
public interface ExecutionRuntimeLifecycle {

    /** Prepares the configured runtime and returns bounded evidence suitable for a suite Artifact. */
    RuntimeLifecycleResult prepare(RuntimeLifecycleRequest request);

    /** Deactivates and cleans up an owned runtime after terminal suite execution. */
    RuntimeLifecycleResult cleanup(RuntimeLifecycleRequest request);

    /** Inputs intentionally owned by execution orchestration rather than an MCP request. */
    record RuntimeLifecycleRequest(
            String projectName,
            String suiteRunId,
            JsonNode workspace,
            JsonNode profile,
            Map<String, Object> persistedEvidence) {

        /** Defensively copies persisted safe evidence. */
        public RuntimeLifecycleRequest {
            persistedEvidence = Map.copyOf(persistedEvidence);
        }
    }

    /** Deterministic lifecycle outcome and bounded evidence for the suite-status Artifact. */
    record RuntimeLifecycleResult(boolean successful, boolean enabled, String reasonCode, Map<String, Object> evidence) {

        /** Validates the deterministic outcome contract. */
        public RuntimeLifecycleResult {
            if (reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("runtime lifecycle reasonCode is required");
            }
            evidence = Map.copyOf(evidence);
        }

        /** Returns a no-runtime outcome for profiles without dynamic attach policy. */
        public static RuntimeLifecycleResult notRequired() {
            return new RuntimeLifecycleResult(true, false, "runtime_lifecycle_not_required", Map.of());
        }

        /** Returns deterministic blocked lifecycle evidence. */
        public static RuntimeLifecycleResult blocked(String reasonCode, Map<String, Object> evidence) {
            return new RuntimeLifecycleResult(false, true, reasonCode, evidence);
        }
    }
}
