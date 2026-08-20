package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves the published execution-export options and workspace context bindings. */
record ExecutionExportOptions(
        boolean includeRuntimeStartup,
        boolean includeHealthcheckGate,
        boolean includeResolvedSecrets,
        String when,
        Map<String, String> contextBindings,
        Map<String, String> contextValues,
        JsonNode workspace) {

    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    static ExecutionExportOptions resolve(JsonNode projectArtifact, ArtifactManagementRequest request) {
        JsonNode workspace = projectArtifact.path("workspaces").isArray()
                && projectArtifact.path("workspaces").size() > 0
                ? projectArtifact.path("workspaces").get(0) : projectArtifact;
        JsonNode defaults = workspace.path("sessionExport");
        boolean startup = booleanValue(request, "includeRuntimeStartup",
                workspaceDefault(defaults, "includeRuntimeStartup"));
        boolean healthcheck = booleanValue(request, "includeHealthcheckGate",
                workspaceDefault(defaults, "includeHealthcheckGate"));
        boolean secrets = request.booleanValue("includeResolvedSecrets").orElse(false);

        Map<String, String> bindings = new LinkedHashMap<>();
        copyBindings(workspace.path("variables").path("contextBindings"), bindings);
        copyBindings(request.input().path("contextBindings"), bindings);
        Map<String, String> values = new LinkedHashMap<>();
        copyValues(request.input().path("contextValues"), values);
        return new ExecutionExportOptions(startup, healthcheck, secrets,
                request.text("when").orElse(null), Map.copyOf(bindings), Map.copyOf(values), workspace);
    }

    private static boolean booleanValue(ArtifactManagementRequest request, String field, boolean fallback) {
        return request.booleanValue(field).orElse(fallback);
    }

    private static boolean workspaceDefault(JsonNode defaults, String field) {
        JsonNode value = defaults.path(field);
        if (!value.isBoolean()) {
            return true;
        }
        return value.booleanValue();
    }

    private static void copyBindings(JsonNode node, Map<String, String> target) {
        if (!node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> {
            String value = entry.getValue().isTextual() ? entry.getValue().asText().trim() : "";
            if (entry.getKey().isBlank() || !ENV_KEY.matcher(value).matches()) {
                throw new ArtifactOperationException(
                        "execution_export_context_binding_invalid",
                        "contextBindings values must be valid environment keys");
            }
            target.put(entry.getKey(), value);
        });
    }

    private static void copyValues(JsonNode node, Map<String, String> target) {
        if (!node.isObject()) {
            return;
        }
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isValueNode() && !value.isNull()) {
                target.put(entry.getKey(), value.asText());
            }
        });
    }
}
