package com.nimbly.mcpjavadevtools.server.core.operation.binding;

import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionContext;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationGuarantee;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationCancellationState;
import java.util.Objects;

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
    /** Declares how this context-aware owner responds to cancellation. */
    default OperationCancellationState cancellationState() {
        return OperationCancellationState.CONTEXT_AWARE_CANCELLATION;
    }

    /** Binds explicit cancellation metadata to a typed owner without a mode-specific interface. */
    static <I, O> ContextAwareOperationExecutor<I, O> declared(
            OperationCancellationState state,
            OperationCancellationGuarantee guarantee,
            ContextAwareOperationExecutor<I, O> owner) {
        Objects.requireNonNull(state, "cancellation state must not be null");
        Objects.requireNonNull(guarantee, "cancellation guarantee must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        if (state == OperationCancellationState.LEGACY_UNVERIFIED_CANCELLATION) {
            throw new IllegalArgumentException("context-aware owner cannot declare legacy cancellation");
        }
        if (state == OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION
                && guarantee != OperationCancellationGuarantee.DELEGATED_DEADLINE) {
            throw new IllegalArgumentException("bounded delegate requires a delegated deadline guarantee");
        }
        return new DeclaredOperationExecutor<>(state, guarantee, owner);
    }
}
