package com.nimbly.mcpjavadevtools.server.mcp.tools.operation.model;

import java.util.List;
import org.springframework.lang.Nullable;

/** Structured MCP result for a bounded operation catalog page. */
public record OperationCatalogMcpResult(
        String status,
        String reasonCode,
        String reason,
        List<OperationCatalogMcpEntry> entries,
        @Nullable String nextCursor,
        int totalMatches) {

    public OperationCatalogMcpResult {
        entries = List.copyOf(entries);
    }
}
