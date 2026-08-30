package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.operation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Typed boundary wrapper for a Performance Suite plan. */
public record PerformanceSuiteOperationArguments(JsonNode input) {

    /** Defensively copies the JSON-shaped suite input. */
    public PerformanceSuiteOperationArguments {
        input = Objects.requireNonNull(input, "suite input must not be null").deepCopy();
    }
}
