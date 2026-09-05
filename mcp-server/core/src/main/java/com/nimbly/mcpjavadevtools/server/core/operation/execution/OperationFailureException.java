package com.nimbly.mcpjavadevtools.server.core.operation.execution;

import java.util.Set;

/** Internal classified failure at the registration binding and normalization boundary. */
public class OperationFailureException extends RuntimeException {

    private static final Set<String> REASON_CODES = Set.of(
            "operation_binding_failed",
            "operation_normalization_failed",
            "operation_output_structure_invalid",
            "operation_output_too_large");

    private final String reasonCode;

    public OperationFailureException(String message, String reasonCode) {
        this(message, reasonCode, null);
    }

    public OperationFailureException(String message, String reasonCode, Throwable cause) {
        super(message, cause);
        if (!REASON_CODES.contains(reasonCode)) {
            throw new IllegalArgumentException("operation failure reason code is not recognized");
        }
        this.reasonCode = reasonCode;
    }

    /** @return stable deterministic reason code exposed by Core execution results */
    public String reasonCode() {
        return reasonCode;
    }
}
