package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;

/** Immutable request envelope for one Regression Suite Core action. */
public record RegressionSuiteRequest(RegressionSuiteAction action, JsonNode input) {

    /** Normalizes omitted input to JSON null. */
    public RegressionSuiteRequest {
        input = input == null ? NullNode.getInstance() : input;
    }
}
