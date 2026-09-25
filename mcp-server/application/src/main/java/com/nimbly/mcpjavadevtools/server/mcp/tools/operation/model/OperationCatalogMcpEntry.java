package com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model;

import java.util.List;

/** Public CDE catalog row containing only canonical operation documentation fields. */
public record OperationCatalogMcpEntry(
        String operationId,
        String api,
        String operation,
        String classification,
        String summary,
        List<String> tags) {

    public OperationCatalogMcpEntry {
        tags = List.copyOf(tags);
    }
}
