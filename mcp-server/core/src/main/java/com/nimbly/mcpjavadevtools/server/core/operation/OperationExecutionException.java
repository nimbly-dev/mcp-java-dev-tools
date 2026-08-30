package com.nimbly.mcpjavadevtools.server.core.operation;

/** Safe failure raised when an explicit operation registration cannot execute. */
public final class OperationExecutionException extends RuntimeException {

    /** Creates a non-sensitive execution failure with the stable reason code. */
    public OperationExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Creates a non-sensitive execution failure without a cause. */
    public OperationExecutionException(String message) {
        super(message);
    }
}
