package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.time.Instant;

/** Parsed execution selection and stable run identity; no persistence or orchestration behavior. */
public record ExecutionInput(String projectName, String executionProfile, String suiteRunId, int maxPlansPerCall,
        ExecutionOrchestrationResult failure) {
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** Returns the stable per-plan identity within this suite run. */
    public String runId(int order) {
        return suiteRunId == null ? "orchestration-" + order : suiteRunId + "-" + order;
    }

    public static ExecutionInput from(JsonNode node) {
        String project = text(node.path("projectName"));
        String profile = text(node.path("executionProfile"));
        if (project == null) {
            return invalid("project_name_required", "provide a non-empty projectName");
        }
        if (profile == null) {
            return invalid("execution_profile_required", "provide a non-empty executionProfile");
        }
        String runId = text(node.path("suiteRunId"));
        if (runId != null && !RUN_ID.matcher(runId).matches()) {
            return invalid("suite_run_id_invalid", "provide a safe suiteRunId");
        }
        int maximum = maximum(node.path("maxPlansPerCall"));
        return maximum < 0 ? invalid("max_plans_per_call_invalid", "provide a positive integer maxPlansPerCall")
                : new ExecutionInput(project, profile, runId, maximum, null);
    }

    private static int maximum(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return 0;
        }
        return node.canConvertToInt() && node.asInt() > 0 ? node.asInt() : -1;
    }

    private static ExecutionInput invalid(String code, String nextAction) {
        return new ExecutionInput(null, null, null, 0, ExecutionOrchestrationResult.blocked(code, nextAction, Map.of()));
    }

    public static String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
    }

    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectName", projectName);
        metadata.put("executionProfile", executionProfile);
        if (suiteRunId != null) {
            metadata.put("suiteRunId", suiteRunId);
        }
        return Map.copyOf(metadata);
    }

    public String suiteRunIdOr(String fallback) {
        return suiteRunId == null ? fallback : suiteRunId;
    }

    public ExecutionInput withAllocatedSuiteRunId() {
        if (suiteRunId != null) {
            return this;
        }
        String allocated = "suite-" + Instant.now().toEpochMilli();
        return new ExecutionInput(projectName, executionProfile, allocated, maxPlansPerCall, failure);
    }
}

