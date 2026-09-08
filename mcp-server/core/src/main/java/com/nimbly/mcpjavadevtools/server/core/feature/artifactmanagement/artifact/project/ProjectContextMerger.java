package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;

/** Released workspace-keyed Project Artifact merge behavior. */
final class ProjectContextMerger {

    JsonNode merge(JsonNode existing, JsonNode incoming) {
        ArrayNode workspaces = JsonNodeFactory.instance.arrayNode();
        existing.path("workspaces").forEach(workspace -> workspaces.add(workspace.deepCopy()));
        for (JsonNode incomingWorkspace : incoming.path("workspaces")) {
            int index = findWorkspace(workspaces, incomingWorkspace.path("projectRoot").asText());
            if (index < 0) {
                workspaces.add(incomingWorkspace.deepCopy());
            } else {
                workspaces.set(index, mergeWorkspace(workspaces.get(index), incomingWorkspace));
            }
        }
        return JsonNodeFactory.instance.objectNode().set("workspaces", workspaces);
    }

    private int findWorkspace(ArrayNode workspaces, String projectRoot) {
        Path incomingRoot = Path.of(projectRoot).toAbsolutePath().normalize();
        for (int index = 0; index < workspaces.size(); index++) {
            Path existingRoot = Path.of(workspaces.path(index).path("projectRoot").asText())
                    .toAbsolutePath().normalize();
            if (existingRoot.equals(incomingRoot)) {
                return index;
            }
        }
        return -1;
    }

    private ObjectNode mergeWorkspace(JsonNode existing, JsonNode incoming) {
        ObjectNode merged = existing.deepCopy();
        incoming.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        mergeNested(merged, existing, incoming, "variables");
        mergeNested(merged, existing, incoming, "sessionExport");
        mergeDefaults(merged, existing, incoming);
        return merged;
    }

    private void mergeDefaults(ObjectNode merged, JsonNode existing, JsonNode incoming) {
        if (!existing.path("defaults").isObject() && !incoming.path("defaults").isObject()) {
            return;
        }
        ObjectNode defaults = shallowMerge(existing.path("defaults"), incoming.path("defaults"));
        if (existing.path("defaults").path("orchestrator").isObject()
                || incoming.path("defaults").path("orchestrator").isObject()) {
            defaults.set("orchestrator", shallowMerge(
                    existing.path("defaults").path("orchestrator"),
                    incoming.path("defaults").path("orchestrator")));
        }
        merged.set("defaults", defaults);
    }

    private void mergeNested(
            ObjectNode merged, JsonNode existing, JsonNode incoming, String field) {
        if (!existing.path(field).isObject() && !incoming.path(field).isObject()) {
            return;
        }
        ObjectNode value = shallowMerge(existing.path(field), incoming.path(field));
        if ("variables".equals(field) && (existing.path(field).path("contextBindings").isObject()
                || incoming.path(field).path("contextBindings").isObject())) {
            value.set("contextBindings", shallowMerge(
                    existing.path(field).path("contextBindings"),
                    incoming.path(field).path("contextBindings")));
        }
        merged.set(field, value);
    }

    private ObjectNode shallowMerge(JsonNode existing, JsonNode incoming) {
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        if (existing.isObject()) {
            existing.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        }
        if (incoming.isObject()) {
            incoming.fields().forEachRemaining(entry -> merged.set(entry.getKey(), entry.getValue()));
        }
        return merged;
    }
}
