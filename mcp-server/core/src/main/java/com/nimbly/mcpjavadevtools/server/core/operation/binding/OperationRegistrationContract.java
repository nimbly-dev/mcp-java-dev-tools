package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;

/** Immutable Java-owned schema and safety contract for one registration. */
public record OperationRegistrationContract(
        OperationSchema inputSchema,
        OperationSchema resultSchema,
        OperationSafetyPolicy safety) {

    /** Validates the executable registration contract. */
    public OperationRegistrationContract {
        Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        Objects.requireNonNull(resultSchema, "resultSchema must not be null");
        Objects.requireNonNull(safety, "safety must not be null");
    }

}
