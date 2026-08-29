package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;

/** Selects the execution profile embedded in the export workspace. */
final class ExecutionExportProfileSelector {

    private ExecutionExportProfileSelector() {
    }

    static JsonNode select(JsonNode workspace, String profileName) {
        if (profileName == null || !workspace.path("executionProfiles").isArray()) {
            return null;
        }
        for (JsonNode profile : workspace.path("executionProfiles")) {
            if (profileName.equals(profile.path("executionProfile").asText(null))) {
                return profile;
            }
        }
        return null;
    }
}
