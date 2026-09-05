package com.nimbly.mcpjavadevtools.server.core.operation.execution;

/** Restores the previous thread-local Core execution context when closed. */
public class OperationExecutionContextScope implements AutoCloseable {

    private final OperationExecutionContext previous;
    private boolean closed;

    OperationExecutionContextScope(OperationExecutionContext previous) {
        this.previous = previous;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            OperationExecutionContext.restore(previous);
        }
    }
}
