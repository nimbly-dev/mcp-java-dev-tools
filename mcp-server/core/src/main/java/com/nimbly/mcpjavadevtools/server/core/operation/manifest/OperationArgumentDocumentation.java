package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/** Human documentation plus Java-derived executable metadata for one argument. */
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
        if (name.isBlank() || description.isBlank() || (type != null && type.isBlank())
                || name.length() > 128 || description.length() > 2048) {
            throw new IllegalArgumentException("operation argument documentation is outside the supported bounds");
        }
        defaultValue = defaultValue == null ? null : defaultValue.deepCopy();
    }

    @Override
    public JsonNode defaultValue() {
        return defaultValue == null ? null : defaultValue.deepCopy();
    }

    /** Creates XML-authored descriptive metadata with no executable claims. */
    public static OperationArgumentDocumentation documented(String name, String description) {
        return new OperationArgumentDocumentation(name, description, null, false, null);
    }

    /** Applies the executable metadata derived from the Java-owned input schema. */
    public OperationArgumentDocumentation withSchema(
            String schemaType, boolean schemaRequired, JsonNode schemaDefault) {
        return new OperationArgumentDocumentation(
                name, description, Objects.requireNonNull(schemaType),
                schemaRequired, schemaDefault);
    }
}
