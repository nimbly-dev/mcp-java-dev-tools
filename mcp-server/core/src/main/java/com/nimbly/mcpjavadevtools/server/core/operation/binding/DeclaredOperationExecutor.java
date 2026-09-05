package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;

/** Immutable declaration and typed callback shared by all explicit cancellation modes. */
class DeclaredOperationExecutor<I, O> implements ContextAwareOperationExecutor<I, O> {

    private final OperationCancellationState state;
    private final OperationCancellationGuarantee guarantee;
    private final ContextAwareOperationExecutor<I, O> owner;

    DeclaredOperationExecutor(OperationCancellationState state,
            OperationCancellationGuarantee guarantee, ContextAwareOperationExecutor<I, O> owner) {
        this.state = state;
        this.guarantee = guarantee;
        this.owner = owner;
    }

    @Override
    public O execute(I request, OperationExecutionContext context) {
        return owner.execute(request, context);
    }

    @Override
    public OperationCancellationState cancellationState() {
        return state;
    }

    @Override
    public OperationCancellationGuarantee cancellationGuarantee() {
        return guarantee;
    }
}
