package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;

/** Immutable request envelope for one Performance Suite Core action. */
public record PerformanceSuiteRequest(PerformanceSuiteAction action, JsonNode input) {

    /** Normalizes omitted action input to JSON null. */
    public PerformanceSuiteRequest {
        input = input == null ? NullNode.getInstance() : input;
    }
}
