package com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model;

import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import java.util.List;
import java.util.Objects;

/** Curated, structured public description of a canonical operation. */
public record OperationMcpDetails(
        String operationId,
        String api,
        String operation,
        String classification,
        String summary,
        String description,
        List<OperationMcpArgument> arguments,
        List<Object> examples,
        List<String> tags,
        Object inputSchema,
        Object resultSchema,
        OperationSafetyPolicy safety) {

    public OperationMcpDetails {
        arguments = List.copyOf(arguments);
        examples = examples.stream().map(OperationMcpJsonValue::immutableCopy).toList();
        inputSchema = OperationMcpJsonValue.immutableCopy(Objects.requireNonNull(inputSchema));
        resultSchema = OperationMcpJsonValue.immutableCopy(Objects.requireNonNull(resultSchema));
    }
}
