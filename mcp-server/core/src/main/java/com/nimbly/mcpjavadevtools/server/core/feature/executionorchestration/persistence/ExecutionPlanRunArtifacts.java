package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute.ExecutionInput;

/** Owns canonical plan-run recovery, terminal interpretation, continuation and evidence serialization. */
public class ExecutionPlanRunArtifacts {
    private final ArtifactManagementFeature artifacts;
    private final ObjectMapper mapper;

    public ExecutionPlanRunArtifacts(ArtifactManagementFeature artifacts, ObjectMapper mapper) {
        this.artifacts = artifacts;
        this.mapper = mapper;
    }

    /** Restores one persisted run through Artifact Management before any new Suite effects. */
    public ExecutionOrchestrationResult recover(ExecutionInput input, String suiteType, JsonNode plan) {
        if (ExecutionInput.text(plan.path("planName")) == null) {
            return null;
        }
        ObjectNode request = runSelector(input, suiteType, plan);
        var prior = artifacts.execute(new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.READ, request));
        JsonNode artifact = mapper.valueToTree(prior.details().get("artifact"));
        if (!"ok".equals(prior.status()) || !artifact.isObject()) {
            return null;
        }
        request.set("payload", artifact);
        var recovered = artifacts.execute(new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.UPSERT, request));
        if (!"ok".equals(recovered.status())) {
            Map<String, Object> metadata = new LinkedHashMap<>(input.metadata());
            metadata.put("planName", plan.path("planName").asText());
            metadata.put("runId", request.path("runId").asText());
            metadata.put("projectionReasonCode", recovered.reasonCode());
            return ExecutionOrchestrationResult.blocked("execution_run_artifact_persist_failed",
                    "restore run storage and retry before resuming execution", metadata);
        }
        return null;
    }

    private ObjectNode runSelector(ExecutionInput input, String suiteType, JsonNode plan) {
        return mapper.createObjectNode().put("projectName", input.projectName()).put("suiteType", suiteType)
                .put("planName", plan.path("planName").asText()).put("runId", input.runId(plan.path("order").asInt()));
    }

    public Map<String, Object> terminalOutcome(ExecutionInput input, String suiteType, JsonNode plan) {
        String planName = ExecutionInput.text(plan.path("planName"));
        if (planName == null) {
            return null;
        }
        ObjectNode request = runSelector(input, suiteType, plan);
        var result = artifacts.execute(new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.READ, request));
        if (!"ok".equals(result.status())) {
            return null;
        }
        JsonNode artifact = mapper.valueToTree(result.details().get("artifact"));
        if (!List.of("pass", "fail", "blocked", "completed", "skipped", "partial_fail")
                .contains(artifact.path("status").asText())) {
            return null;
        }
        Map<String, Object> outcome = mapper.convertValue(artifact, Map.class);
        outcome.put("order", plan.path("order").asInt());
        outcome.put("planName", planName);
        outcome.put("runId", input.runId(plan.path("order").asInt()));
        return Map.copyOf(outcome);
    }

    public void addRegressionContinuation(ObjectNode envelope, ExecutionInput input, String planName, String runId) {
        ObjectNode request = mapper.createObjectNode().put("projectName", input.projectName())
                .put("suiteType", "regression").put("planName", planName).put("runId", runId);
        var prior = artifacts.execute(new ArtifactManagementRequest(ArtifactType.RUN_RESULT, ArtifactAction.READ, request));
        JsonNode artifact = mapper.valueToTree(prior.details().get("artifact"));
        if ("ok".equals(prior.status()) && "in_progress".equals(artifact.path("status").asText())) {
            envelope.set("continuation", artifact.path("details").path("continuation"));
        }
    }

    public Map<String, Object> persist(ExecutionInput input, String suiteType, JsonNode plan, Map<String, Object> outcome) {
        String planName = plan.path("planName").asText();
        String runId = input.runId(plan.path("order").asInt());
        ObjectNode request = mapper.createObjectNode();
        request.put("projectName", input.projectName());
        request.put("suiteType", suiteType);
        request.put("planName", planName);
        request.put("runId", runId);
        request.set("payload", runPayload(input, outcome, runId));
        var persisted = artifacts.execute(new ArtifactManagementRequest(
                ArtifactType.RUN_RESULT, ArtifactAction.UPSERT, request));
        if (!"ok".equals(persisted.status())) {
            return Map.of("order", plan.path("order").asInt(), "planName", planName,
                    "status", "blocked", "reasonCode", "execution_run_artifact_persist_failed",
                    "details", Map.of("runId", runId));
        }
        Map<String, Object> details = new LinkedHashMap<>(outcome);
        details.put("runId", runId);
        return Map.copyOf(details);
    }

    private ObjectNode runPayload(ExecutionInput input, Map<String, Object> outcome, String runId) {
        ObjectNode payload = mapper.valueToTree(outcome);
        JsonNode correlation = payload.path("details").path("correlation");
        if (correlation.isObject()) {
            payload.set("correlation", correlation);
        }
        payload.put("status", persistedStatus(outcome));
        for (String field : List.of("steps", "watchers", "externalVerification", "probeVerification",
                "metrics", "thresholdResults", "msta", "profiler", "coverage", "findings", "securityMode", "requiredLineHits")) {
            if (payload.path("details").has(field)) {
                payload.set(field, payload.path("details").path(field));
            }
        }
        if ("in_progress".equals(payload.path("status").asText())) {
            payload.set("activePhase", payload.path("details").path("continuation").path("phase"));
        }
        payload.put("executionProfile", input.executionProfile());
        payload.put("suiteRunId", input.suiteRunIdOr(runId));
        payload.put("startedAt", Instant.now().toString());
        payload.put("endedAt", Instant.now().toString());
        return payload;
    }

    private static String persistedStatus(Map<String, Object> outcome) {
        Object details = outcome.get("details");
        if (details instanceof Map<?, ?> values && values.get("runStatus") instanceof String status) {
            return status;
        }
        return String.valueOf(outcome.get("status"));
    }

}
