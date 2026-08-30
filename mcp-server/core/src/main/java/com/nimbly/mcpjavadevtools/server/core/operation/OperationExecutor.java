package com.nimbly.mcpjavadevtools.server.core.operation;

/** Explicit executable owner boundary for one registered typed operation. */
@FunctionalInterface
public interface OperationExecutor<I, O> {

    /** @param request typed request @return typed result */
    O execute(I request);
}
