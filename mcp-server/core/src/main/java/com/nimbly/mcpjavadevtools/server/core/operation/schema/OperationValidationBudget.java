package com.nimbly.mcpjavadevtools.server.core.operation.schema;

/** Cooperative budget consulted while validating one already materialized JSON payload. */
@FunctionalInterface
public interface OperationValidationBudget {

    /** @return whether validation must stop before doing more work */
    boolean expired();
}
