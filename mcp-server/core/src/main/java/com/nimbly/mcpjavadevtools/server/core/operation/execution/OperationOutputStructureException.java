package com.nimbly.mcpjavadevtools.server.core.operation.execution;

/** Signals that normalized JSON exceeds the Core structural safety ceilings. */
public class OperationOutputStructureException extends RuntimeException {

    /** Creates a deterministic structural-output failure. */
    public OperationOutputStructureException(String message) {
        super(message);
    }
}
