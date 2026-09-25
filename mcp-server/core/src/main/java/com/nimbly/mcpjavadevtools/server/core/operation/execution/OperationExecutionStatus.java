package com.nimbly.mcpjavadevtools.server.core.operation.execution;

/** Closed result statuses for aggregate operation execution. */
public enum OperationExecutionStatus {
    SUCCEEDED,
    INVALID_INPUT,
    UNSUPPORTED_OPERATION,
    CONFIRMATION_REQUIRED,
    TIMEOUT,
    CANCELLED,
    FAILED
}
