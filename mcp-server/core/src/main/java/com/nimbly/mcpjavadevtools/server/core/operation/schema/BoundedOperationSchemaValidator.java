package com.nimbly.mcpjavadevtools.server.core.operation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Public bounded validation entry point for Core execution deadlines. */
public class BoundedOperationSchemaValidator {

    private BoundedOperationSchemaValidator() {
    }

    /** Validates JSON tree structure using the supplied cooperative budget. */
    public static List<String> treeViolations(
            JsonNode input, OperationValidationBudget budget) {
        return OperationJsonTreeLimits.violations(input, budget);
    }

    /** Validates the schema and input while consulting the supplied cooperative budget. */
    public static List<String> schemaViolations(
            OperationSchema schema, JsonNode input, OperationValidationBudget budget) {
        return OperationSchemaValidator.violations(schema, input, budget);
    }
}
