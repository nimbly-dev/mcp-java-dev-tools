package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;

/** Owner boundary whose implementation supplies an explicit bounded-continuation proof. */
public interface BoundedNonCancellableOperationExecutor<I, O> extends OperationExecutor<I, O> {

    /** Declares the concrete continuation guarantee owned by this implementation. */
    OperationCancellationGuarantee cancellationGuarantee();
}
