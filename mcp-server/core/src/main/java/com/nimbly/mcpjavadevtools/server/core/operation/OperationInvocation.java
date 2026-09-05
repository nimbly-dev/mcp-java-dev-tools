package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationJsonSize;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyLimits;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationJsonTreeLimits;
import java.util.Objects;

/** Exact aggregate invocation envelope; no implicit Describe step is required. */
public record OperationInvocation(
        OperationId operationId,
        JsonNode input,
        boolean confirmed,
        boolean allowDeprecated) {

    /** Normalizes absent JSON input while retaining exact operation identity. */
    public OperationInvocation {
        input = input == null ? NullNode.getInstance() : boundedSnapshot(input);
    }

    /** Creates an invocation with no confirmation and no deprecated override. */
    public OperationInvocation(OperationId operationId, JsonNode input) {
        this(operationId, input, false, false);
    }

    /** Creates an invocation with optional safety confirmation. */
    public OperationInvocation(OperationId operationId, JsonNode input, boolean confirmed) {
        this(operationId, input, confirmed, false);
    }

    @Override
    public JsonNode input() {
        return Objects.requireNonNull(input, "input must not be null").deepCopy();
    }

    /** Returns the source input to the package-owned execution bridge. */
    JsonNode rawInput() {
        return Objects.requireNonNull(input, "input must not be null");
    }

    private static JsonNode boundedSnapshot(JsonNode value) {
        if (!OperationJsonTreeLimits.violations(value).isEmpty()
                || OperationJsonSize.measure(value, OperationSafetyLimits.MAX_INPUT_BYTES) < 0) {
            throw new IllegalArgumentException("invocation input exceeds its safety bound");
        }
        return value.deepCopy();
    }
}
