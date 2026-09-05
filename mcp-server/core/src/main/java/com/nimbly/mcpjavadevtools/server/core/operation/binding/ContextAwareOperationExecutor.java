package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;

/** Typed owner boundary for operations that cooperatively consume Core execution context. */
@FunctionalInterface
public interface ContextAwareOperationExecutor<I, O> extends OperationExecutor<I, O> {

    /** Executes one request with the immutable deadline and cooperative cancellation signal. */
    O execute(I request, OperationExecutionContext context);

    @Override
    default O execute(I request) {
        return execute(request, OperationExecutionContext.current());
    }

    /** @return explicit proof shape for mutating cancellation */
    default OperationCancellationGuarantee cancellationGuarantee() {
        return OperationCancellationGuarantee.NONE;
    }
}
