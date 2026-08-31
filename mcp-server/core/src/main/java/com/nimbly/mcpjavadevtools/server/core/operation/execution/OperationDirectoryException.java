package com.nimbly.mcpjavadevtools.server.core.operation.execution;

import java.util.Objects;

/** Deterministic, transport-neutral failure for catalog and describe requests. */
public final class OperationDirectoryException extends IllegalArgumentException {

    private final OperationExecutionStatus status;
    private final String reasonCode;

    /** Creates a bounded Core directory failure without implementation details. */
    public OperationDirectoryException(
            OperationExecutionStatus status, String reasonCode, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        if (reasonCode.isBlank() || reasonCode.length() > 128 || message.length() > 2048) {
            throw new IllegalArgumentException("directory failure text is outside the supported bounds");
        }
    }

    /** @return stable failure status for transport normalization */
    public OperationExecutionStatus status() {
        return status;
    }

    /** @return stable failure reason code for transport normalization */
    public String reasonCode() {
        return reasonCode;
    }
}
