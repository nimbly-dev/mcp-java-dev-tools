package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;

/** Immutable request envelope for one execution orchestration action. */
public record ExecutionOrchestrationRequest(ExecutionOrchestrationAction action, JsonNode input) {

    /** Normalizes omitted input to JSON null. */
    public ExecutionOrchestrationRequest {
        input = input == null ? NullNode.getInstance() : input;
    }
}
