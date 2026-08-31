package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Objects;

/** Immutable JSON schema owned by a typed Core request or result class. */
public record OperationSchema(JsonNode definition) {

    /** Validates and defensively copies one object-shaped schema. */
    public OperationSchema {
        Objects.requireNonNull(definition, "schema definition must not be null");
        if (!definition.isObject()) {
            throw new IllegalArgumentException("operation schema definition must be a JSON object");
        }
        OperationSchemaRules.validate(definition);
        definition = definition.deepCopy();
    }

    @Override
    public JsonNode definition() {
        return definition.deepCopy();
    }

    /** @return an empty schema that accepts every JSON value */
    public static OperationSchema empty() {
        return new OperationSchema(JsonNodeFactory.instance.objectNode()
                .put("$schema", OperationSchemaRules.DRAFT_2020_12));
    }
}
