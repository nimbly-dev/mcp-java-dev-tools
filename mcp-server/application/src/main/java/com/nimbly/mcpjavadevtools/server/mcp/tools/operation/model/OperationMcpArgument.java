package com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model;

import org.springframework.lang.Nullable;

/** Public argument documentation without historical Tool/action alias metadata. */
public record OperationMcpArgument(
        String name,
        String description,
        String type,
        boolean required,
        @Nullable Object defaultValue) {

    public OperationMcpArgument {
        defaultValue = OperationMcpJsonValue.immutableCopy(defaultValue);
    }
}
