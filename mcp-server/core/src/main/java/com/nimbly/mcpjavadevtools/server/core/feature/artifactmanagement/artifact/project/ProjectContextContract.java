package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Project-context contract validation and bounded query projection. */
final class ProjectContextContract {

    void validate(JsonNode artifact) {
        ArtifactManagementSupport.requireObject(artifact, "project_artifact_invalid",
                "project Artifact must be a JSON object");
        JsonNode workspaces = artifact.get("workspaces");
        if (workspaces == null || !workspaces.isArray() || workspaces.isEmpty()) {
            throw new ArtifactOperationException("project_artifact_invalid", "workspaces[] is required");
        }
        for (JsonNode workspace : workspaces) {
            if (!workspace.isObject() || !workspace.path("projectRoot").isTextual()
                    || workspace.path("projectRoot").asText().isBlank()) {
                throw new ArtifactOperationException("workspace_root_invalid", "workspace projectRoot is required");
            }
            if (!workspace.path("defaults").isObject()) {
                throw new ArtifactOperationException("project_artifact_invalid", "workspace defaults are required");
            }
            validateProfiles(workspace);
        }
    }

    void validateScope(ArtifactManagementRequest request, JsonNode artifact) {
        if (request.text("projectRootAbs").isEmpty()) {
            return;
        }
        String requested = Path.of(request.text("projectRootAbs").orElseThrow())
                .toAbsolutePath().normalize().toString();
        for (JsonNode workspace : artifact.path("workspaces")) {
            if (Path.of(workspace.path("projectRoot").asText()).toAbsolutePath().normalize()
                    .toString().equals(requested)) {
                return;
            }
        }
        throw new ArtifactOperationException("project_scope_mismatch",
                "projectName and projectRootAbs do not resolve to the same project scope");
    }

    Map<String, Object> details(String projectName, JsonNode artifact, ArtifactManagementRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("projectName", projectName);
        JsonNode query = request.child("query").orElse(null);
        JsonNode selectors = query == null ? null : query.get("select");
        if (selectors == null || !selectors.isArray() || selectors.isEmpty()) {
            details.put("summary", summary(artifact));
            return details;
        }
        JsonNode workspace = artifact.path("workspaces").path(0);
        for (JsonNode selector : selectors) {
            switch (selector.asText()) {
                case "artifact" -> details.put("artifact", artifact);
                case "summary" -> details.put("summary", summary(artifact));
                case "workspaces" -> details.put("workspaces", artifact.path("workspaces"));
                case "executionProfiles" -> details.put("executionProfiles", profiles(workspace, query));
                case "runtimeContexts" -> details.put("runtimeContexts", workspace.path("runtimeContexts"));
                case "scripts" -> details.put("scripts", workspace.path("scripts"));
                case "runPrerequisites" -> details.put("runPrerequisites", workspace.path("runPrerequisites"));
                default -> { }
            }
        }
        if (details.size() == 1) {
            details.put("summary", summary(artifact));
        }
        return details;
    }

    JsonNode merge(JsonNode original, JsonNode update) {
        if (!original.isObject() || !update.isObject()) {
            return update;
        }
        ObjectNode merged = (ObjectNode) original.deepCopy();
        update.fields().forEachRemaining(entry -> {
            JsonNode current = merged.get(entry.getKey());
            merged.set(entry.getKey(), current == null ? entry.getValue() : merge(current, entry.getValue()));
        });
        return merged;
    }

    private void validateProfiles(JsonNode workspace) {
        JsonNode profiles = workspace.get("executionProfiles");
        if (profiles == null || profiles.isNull()) {
            return;
        }
        if (!profiles.isArray()) {
            throw new ArtifactOperationException("execution_profile_invalid", "executionProfiles must be an array");
        }
        for (JsonNode profile : profiles) {
            ArtifactManagementSupport.requireObject(profile, "execution_profile_invalid",
                    "execution profile must be an object");
            if (!profile.path("executionProfile").isTextual()
                    || profile.path("executionProfile").asText().isBlank()
                    || !java.util.List.of("stop_on_fail", "continue_on_fail")
                    .contains(profile.path("executionPolicy").asText(""))) {
                throw new ArtifactOperationException("execution_profile_invalid",
                        "executionProfile and executionPolicy are required");
            }
            if (!java.util.List.of("regression", "performance", "security")
                    .contains(profile.path("suiteType").asText("regression"))) {
                throw new ArtifactOperationException("execution_profile_invalid",
                        "execution profile suiteType is invalid");
            }
            JsonNode plans = profile.path("plans");
            if (!plans.isArray() || plans.isEmpty()) {
                throw new ArtifactOperationException("execution_profile_invalid",
                        "execution profile plans[] is required");
            }
            for (int index = 0; index < plans.size(); index++) {
                JsonNode plan = plans.get(index);
                if (!plan.isObject() || plan.path("order").asInt(0) != index + 1
                        || !plan.path("planName").isTextual() || plan.path("planName").asText().isBlank()
                        || !java.util.List.of("inherit", "stop", "continue")
                        .contains(plan.path("onFail").asText("inherit"))) {
                    throw new ArtifactOperationException("execution_profile_invalid",
                            "execution profile plans must be sequential and named");
                }
            }
        }
    }

    private Map<String, Object> summary(JsonNode artifact) {
        JsonNode workspace = artifact.path("workspaces").path(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workspaceCount", artifact.path("workspaces").size());
        result.put("executionProfileCount", arraySize(workspace.get("executionProfiles")));
        result.put("runtimeContextCount", arraySize(workspace.get("runtimeContexts")));
        return result;
    }

    private JsonNode profiles(JsonNode workspace, JsonNode query) {
        JsonNode values = workspace.path("executionProfiles");
        String selected = query == null ? null : query.path("executionProfile").asText(null);
        if (selected == null || !values.isArray()) {
            return values;
        }
        var result = new com.fasterxml.jackson.databind.node.ArrayNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        for (JsonNode profile : values) {
            if (selected.equals(profile.path("executionProfile").asText())) {
                result.add(profile);
            }
        }
        return result;
    }

    private static int arraySize(JsonNode value) {
        return value != null && value.isArray() ? value.size() : 0;
    }
}
