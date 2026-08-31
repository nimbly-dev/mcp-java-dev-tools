package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.operation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Typed boundary wrapper owned by the Regression Suite operation bindings. */
public record RegressionSuiteOperationArguments(JsonNode input) {

    /** Defensively copies the JSON-shaped regression-suite input. */
    public RegressionSuiteOperationArguments {
        input = Objects.requireNonNull(input, "suite input must not be null").deepCopy();
    }
}
