package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;

/** Context-aware owner boundary backed by an independently bounded delegate. */
@FunctionalInterface
public interface BoundedDelegateOperationExecutor<I, O>
        extends ContextAwareOperationExecutor<I, O> {

    @Override
    default OperationCancellationGuarantee cancellationGuarantee() {
        return OperationCancellationGuarantee.DELEGATED_DEADLINE;
    }
}
