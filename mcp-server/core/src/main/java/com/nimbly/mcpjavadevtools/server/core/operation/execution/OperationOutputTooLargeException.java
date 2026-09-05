package com.nimbly.mcpjavadevtools.server.core.operation.execution;

/** Internal signal preserving the stable oversized-output reason at the typed boundary. */
public class OperationOutputTooLargeException extends RuntimeException {

    public OperationOutputTooLargeException(String message) {
        super(message);
    }
}
