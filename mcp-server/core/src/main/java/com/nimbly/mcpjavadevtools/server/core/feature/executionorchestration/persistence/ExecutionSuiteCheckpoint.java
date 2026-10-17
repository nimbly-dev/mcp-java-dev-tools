package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute.ExecutionInput;

/** Owns suite checkpoint compatibility, fingerprinting and lifecycle evidence through the existing store. */
public class ExecutionSuiteCheckpoint {
    private final ExecutionSuiteStateStore suiteState;
    private final ObjectMapper mapper;

    public ExecutionSuiteCheckpoint(ExecutionSuiteStateStore suiteState, ObjectMapper mapper) {
        this.suiteState = suiteState;
        this.mapper = mapper;
    }

    /** Returns persisted context for filtering by the context policy. */
    public Object suiteContext(ExecutionInput input) {
        return suiteState.read(input.projectName(), input.suiteRunId())
                .map(value -> value.get("suiteContext")).filter(Map.class::isInstance).orElse(null);
    }

    /** Returns only the persisted lifecycle section for the existing lifecycle owner. */
    public Map<String, Object> lifecycleEvidence(ExecutionInput input) {
        return suiteState.read(input.projectName(), input.suiteRunId())
                .map(value -> value.get("runtimeLifecycle"))
                .filter(Map.class::isInstance).map(Map.class::cast).orElse(Map.of());
    }

    public Map<String, Object> completedLifecycle(ExecutionInput input) {
        var persisted = suiteState.read(input.projectName(), input.suiteRunId());
        if (persisted.isEmpty()) {
            return null;
        }
        Map<String, Object> state = persisted.get();
        if (!List.of("pass", "fail", "partial_fail").contains(state.get("status"))
                || !Objects.equals(state.get("nextPlanOrder"), 0)) {
            return null;
        }
        Object evidence = state.get("runtimeLifecycle");
        if (evidence instanceof Map<?, ?> values && "terminal".equals(values.get("status"))
                && "ok".equals(values.get("cleanupStatus"))) {
            return mapper.convertValue(evidence, Map.class);
        }
        return null;
    }

    public boolean persistLifecycle(ExecutionInput input, JsonNode profile, Map<String, Object> evidence) {
        Map<String, Object> payload = suiteState.read(input.projectName(), input.suiteRunId())
                .map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
        payload.put("resultType", "execution_orchestration");
        payload.put("action", "execute");
        payload.put("projectName", input.projectName());
        payload.put("executionProfile", input.executionProfile());
        payload.put("suiteRunId", input.suiteRunId());
        payload.put("suiteType", profile.path("suiteType").asText());
        payload.put("profileFingerprint", profileFingerprint(profile));
        payload.put("status", "in_progress");
        payload.put("statusArtifactPath", statusArtifactPath(input));
        payload.put("runtimeLifecycle", evidence);
        return suiteState.write(input.projectName(), input.suiteRunId(), Map.copyOf(payload)).isPresent();
    }

    public ExecutionOrchestrationResult validate(ExecutionInput input, JsonNode profile) {
        var checkpoint = suiteState.read(input.projectName(), input.suiteRunId());
        if (checkpoint.isEmpty()) {
            return null;
        }
        JsonNode artifact = mapper.valueToTree(checkpoint.get());
        boolean matches = input.projectName().equals(artifact.path("projectName").asText())
                && input.executionProfile().equals(artifact.path("executionProfile").asText())
                && profile.path("suiteType").asText().equals(artifact.path("suiteType").asText());
        if (!matches) {
            return ExecutionOrchestrationResult.blocked("execution_suite_checkpoint_context_mismatch",
                "resume the suiteRunId only with its original project and executionProfile", input.metadata());
        }
        String fingerprint = ExecutionInput.text(artifact.path("profileFingerprint"));
        if (fingerprint == null || !fingerprint.equals(profileFingerprint(profile))) {
            return ExecutionOrchestrationResult.blocked("execution_suite_checkpoint_definition_mismatch",
                    "start a new suiteRunId after changing the execution profile definition", input.metadata());
        }
        return null;
    }

    public ExecutionOrchestrationResult persist(
            ExecutionInput input, JsonNode profile, ExecutionOrchestrationResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resultType", "execution_orchestration");
        payload.put("action", "execute");
        payload.put("projectName", input.projectName());
        payload.put("executionProfile", input.executionProfile());
        payload.put("suiteRunId", input.suiteRunId());
        payload.put("suiteType", profile.path("suiteType").asText());
        payload.put("profileFingerprint", profileFingerprint(profile));
        payload.put("status", result.status());
        payload.put("reasonCode", result.reasonCode());
        payload.put("executionPolicy", profile.path("executionPolicy").asText());
        payload.put("statusArtifactPath", statusArtifactPath(input));
        payload.putAll(result.details());
        payload.put("nextPlanOrder", nextPlanOrder(result.details()));
        if (suiteState.write(input.projectName(), input.suiteRunId(), Map.copyOf(payload)).isEmpty()) {
            return ExecutionOrchestrationResult.blocked("execution_suite_checkpoint_persist_failed",
                    "restore suite checkpoint storage and retry", input.metadata());
        }
        Map<String, Object> details = new LinkedHashMap<>(result.details());
        details.put("statusArtifactPath", statusArtifactPath(input));
        return new ExecutionOrchestrationResult(
                result.status(), result.reasonCode(), result.nextAction(), result.reasonMeta(), details);
    }

    private static String statusArtifactPath(ExecutionInput input) {
        return ".mcpjvm/" + input.projectName() + "/suite-runs/" + input.suiteRunId()
                + "/execution_orchestration.result.json";
    }

    private static int nextPlanOrder(Map<String, Object> details) {
        Object value = details.get("nextPlanOrder");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String profileFingerprint(JsonNode profile) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(profile).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte entry : digest) {
                value.append(String.format("%02x", entry));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            StringBuilder value = new StringBuilder("{");
            for (String field : fields) {
                if (value.length() > 1) {
                    value.append(',');
                }
                value.append(new ObjectMapper().valueToTree(field).toString()).append(':')
                        .append(canonical(node.path(field)));
            }
            return value.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder value = new StringBuilder("[");
            for (JsonNode entry : node) {
                if (value.length() > 1) {
                    value.append(',');
                }
                value.append(canonical(entry));
            }
            return value.append(']').toString();
        }
        return node.toString();
    }

}
