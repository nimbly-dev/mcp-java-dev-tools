package com.nimbly.mcpjavadevtools.server.core.operation.safety;

/** Registration migration states for operation cancellation support. */
public enum OperationCancellationState {
    LEGACY_UNVERIFIED_CANCELLATION,
    CONTEXT_AWARE_CANCELLATION,
    BOUNDED_DELEGATE_CANCELLATION,
    NOT_CANCELLABLE
}
