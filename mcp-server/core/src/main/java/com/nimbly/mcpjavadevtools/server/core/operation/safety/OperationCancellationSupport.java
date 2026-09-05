package com.nimbly.mcpjavadevtools.server.core.operation.safety;

import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedNonCancellableOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.BoundedDelegateOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.ContextAwareOperationExecutor;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationExecutor;
import java.util.Objects;

/** Resolves and validates the cancellation state of an explicit registration. */
public class OperationCancellationSupport {

    private OperationCancellationSupport() {
    }

    /** Resolves the migration state without inferring proof from thread interruption. */
    public static OperationCancellationState state(
            OperationExecutor<?, ?> executor, OperationSafetyPolicy policy) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (executor instanceof BoundedNonCancellableOperationExecutor<?, ?>) {
            return OperationCancellationState.NOT_CANCELLABLE;
        }
        if (!policy.cancellationSupported()) {
            return OperationCancellationState.NOT_CANCELLABLE;
        }
        if (executor instanceof BoundedDelegateOperationExecutor<?, ?>) {
            return OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION;
        }
        if (executor instanceof ContextAwareOperationExecutor<?, ?>) {
            return OperationCancellationState.CONTEXT_AWARE_CANCELLATION;
        }
        return OperationCancellationState.LEGACY_UNVERIFIED_CANCELLATION;
    }

    /** Resolves the explicit mutation cancellation guarantee carried by an executor. */
    public static OperationCancellationGuarantee guarantee(OperationExecutor<?, ?> executor) {
        Objects.requireNonNull(executor, "executor must not be null");
        if (executor instanceof BoundedDelegateOperationExecutor<?, ?> bounded) {
            return Objects.requireNonNull(
                    bounded.cancellationGuarantee(), "cancellation guarantee must not be null");
        }
        if (executor instanceof ContextAwareOperationExecutor<?, ?> contextAware) {
            return Objects.requireNonNull(
                    contextAware.cancellationGuarantee(), "cancellation guarantee must not be null");
        }
        if (executor instanceof BoundedNonCancellableOperationExecutor<?, ?>) {
            return Objects.requireNonNull(
                    ((BoundedNonCancellableOperationExecutor<?, ?>) executor).cancellationGuarantee(),
                    "cancellation guarantee must not be null");
        }
        return OperationCancellationGuarantee.NONE;
    }

    /** Rejects cancellation declarations that cannot provide bounded mutation semantics. */
    public static void validate(
            OperationExecutor<?, ?> executor,
            OperationSafetyPolicy policy,
            boolean migrationCompatibilityMode) {
        OperationCancellationState state = state(executor, policy);
        if (state == OperationCancellationState.LEGACY_UNVERIFIED_CANCELLATION
                && !migrationCompatibilityMode) {
            throw new IllegalArgumentException(
                    "legacy cancellation registration requires migration compatibility mode");
        }
        if (executor instanceof BoundedNonCancellableOperationExecutor<?, ?>
                && policy.cancellationSupported()) {
            throw new IllegalArgumentException(
                    "non-cancellable operation must disable cancellation support");
        }
        if ((state == OperationCancellationState.CONTEXT_AWARE_CANCELLATION
                || state == OperationCancellationState.BOUNDED_DELEGATE_CANCELLATION)
                && !policy.readOnly() && guarantee(executor) == OperationCancellationGuarantee.NONE) {
            throw new IllegalArgumentException(
                    "mutating context-aware operation requires a cancellation guarantee");
        }
        if (state == OperationCancellationState.NOT_CANCELLABLE
                && guarantee(executor) != OperationCancellationGuarantee.DETERMINISTIC_CONTINUATION) {
            throw new IllegalArgumentException(
                    "non-cancellable operation requires bounded continuation semantics");
        }
    }
}
