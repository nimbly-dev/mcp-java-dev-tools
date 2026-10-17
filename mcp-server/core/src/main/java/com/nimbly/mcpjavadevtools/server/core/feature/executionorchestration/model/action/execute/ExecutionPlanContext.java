package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.execute;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Profile, workspace and current promoted values supplied to one plan. */
public record ExecutionPlanContext(JsonNode profile, JsonNode workspace, Map<String, Object> suiteContext) {

    public ExecutionPlanContext {
        suiteContext = Map.copyOf(suiteContext);
    }
}
