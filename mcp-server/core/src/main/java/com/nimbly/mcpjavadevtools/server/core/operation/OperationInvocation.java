package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Objects;

/** Exact aggregate invocation envelope; no implicit Describe step is required. */
public record OperationInvocation(
        OperationId operationId,
        JsonNode input,
        boolean confirmed,
        boolean allowDeprecated) {

    /** Normalizes absent JSON input while retaining exact operation identity. */
    public OperationInvocation {
        input = input == null ? NullNode.getInstance() : input.deepCopy();
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
}
