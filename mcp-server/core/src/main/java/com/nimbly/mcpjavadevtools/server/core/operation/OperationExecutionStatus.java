package com.nimbly.mcpjavadevtools.server.core.operation;

/** Closed result statuses for aggregate operation execution. */
public enum OperationExecutionStatus {
    SUCCEEDED,
    INVALID_INPUT,
    UNSUPPORTED_OPERATION,
    DEPRECATED_OPERATION,
    CONFIRMATION_REQUIRED,
    TIMEOUT,
    CANCELLED,
    FAILED
}
