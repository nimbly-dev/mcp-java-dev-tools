package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;

/** Deterministic, redaction-safe aggregate execution envelope. */
public record OperationExecutionResult(
        OperationId operationId,
        OperationExecutionStatus status,
        JsonNode result,
        String reasonCode,
        String reason,
        Map<String, String> metadata) {

    /** Validates and defensively copies an execution envelope. */
    public OperationExecutionResult {
        Objects.requireNonNull(status, "execution status must not be null");
        Objects.requireNonNull(reasonCode, "execution reason code must not be null");
        Objects.requireNonNull(reason, "execution reason must not be null");
        if (reasonCode.isBlank() || reasonCode.length() > 128 || reason.length() > 2048) {
            throw new IllegalArgumentException("execution result text is outside the supported bounds");
        }
        result = result == null ? NullNode.getInstance() : result.deepCopy();
        metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** @return whether execution completed successfully */
    public boolean successful() {
        return status == OperationExecutionStatus.SUCCEEDED;
    }

    @Override
    public JsonNode result() {
        return result.deepCopy();
    }
}
