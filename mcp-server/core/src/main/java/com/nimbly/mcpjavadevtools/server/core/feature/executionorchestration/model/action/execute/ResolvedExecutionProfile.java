package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute;

import com.fasterxml.jackson.databind.JsonNode;

/** The selected profile and its owning workspace. */
public record ResolvedExecutionProfile(JsonNode profile, JsonNode workspace) {
}
