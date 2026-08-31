package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.operation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Typed boundary wrapper owned by the Security Suite operation bindings. */
public record SecuritySuiteOperationArguments(JsonNode input) {

    /** Defensively copies the JSON-shaped security-suite input. */
    public SecuritySuiteOperationArguments {
        input = Objects.requireNonNull(input, "suite input must not be null").deepCopy();
    }
}
