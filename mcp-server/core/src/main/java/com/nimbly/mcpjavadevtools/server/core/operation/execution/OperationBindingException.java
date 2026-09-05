package com.nimbly.mcpjavadevtools.server.core.operation.execution;

/** Non-sensitive failure raised when a registered argument binder rejects its input. */
public class OperationBindingException extends RuntimeException {

    public OperationBindingException(String message, Throwable cause) {
        super(message, cause);
    }

    public OperationBindingException(String message) {
        super(message);
    }
}
