package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Typed boundary wrapper for a suite plan in the flat #610 baseline. */
public record SuiteOperationArguments(JsonNode input) {

    /** Defensively copies the JSON-shaped suite input. */
    public SuiteOperationArguments {
        input = Objects.requireNonNull(input, "suite input must not be null").deepCopy();
    }
}