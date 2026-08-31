package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Documentation and validation claims for one typed operation argument. */
public record OperationArgumentDocumentation(
        String name,
        String description,
        String type,
        boolean required,
        JsonNode defaultValue) {

    /** Validates and defensively copies one argument description. */
    public OperationArgumentDocumentation {
        Objects.requireNonNull(name, "argument name must not be null");
        Objects.requireNonNull(description, "argument description must not be null");
        Objects.requireNonNull(type, "argument type must not be null");
        if (name.isBlank() || description.isBlank() || type.isBlank()
                || name.length() > 128 || description.length() > 2048) {
            throw new IllegalArgumentException("operation argument documentation is outside the supported bounds");
        }
        defaultValue = defaultValue == null ? null : defaultValue.deepCopy();
    }

    @Override
    public JsonNode defaultValue() {
        return defaultValue == null ? null : defaultValue.deepCopy();
    }
}
