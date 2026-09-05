package com.nimbly.mcpjavadevtools.server.core.operation.safety;

/** Explicit proof shape for cancellation of a mutating operation. */
public enum OperationCancellationGuarantee {
    NONE,
    IDEMPOTENT,
    ROLLBACK,
    DELEGATED_DEADLINE,
    DETERMINISTIC_CONTINUATION
}
