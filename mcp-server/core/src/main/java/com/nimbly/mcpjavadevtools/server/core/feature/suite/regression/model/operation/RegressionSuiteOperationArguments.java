package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.operation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Typed boundary wrapper for a Regression Suite plan. */
public record RegressionSuiteOperationArguments(JsonNode input) {

    /** Defensively copies the JSON-shaped suite input. */
    public RegressionSuiteOperationArguments {
        input = Objects.requireNonNull(input, "suite input must not be null").deepCopy();
    }
}
