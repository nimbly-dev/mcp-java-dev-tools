package com.nimbly.mcpjavadevtools.server.core.operation.execution;

/** Non-sensitive failure raised when a registered result normalizer is invalid or unstable. */
public class OperationNormalizationException extends RuntimeException {

    public OperationNormalizationException(String message, Throwable cause) {
        super(message, cause);
    }

    public OperationNormalizationException(String message) {
        super(message);
    }
}
