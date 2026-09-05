package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Bounded recursive-schema validation state shared by one validation call. */
record OperationSchemaValidationContext(
        JsonNode root,
        String path,
        List<String> violations,
        int depth,
        OperationValidationBudget budget) {

    OperationSchemaValidationContext child(String childPath) {
        return new OperationSchemaValidationContext(root, childPath, violations, depth + 1, budget);
    }

    JsonNode referenceTarget(JsonNode schema) {
        return root.path("$defs").get(
                schema.get("$ref").asText().substring("#/$defs/".length()));
    }

    boolean expired() {
        return budget.expired();
    }
}
