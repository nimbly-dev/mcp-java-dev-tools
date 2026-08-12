package com.nimbly.mcpjavadevtools.server.mcp.tools.jvmlifecycle;

import org.jspecify.annotations.Nullable;

/**
 * MCP-bound action fields translated into the Core request factory.
 */
public record JvmLifecycleMcpActionInput(
        @Nullable String pid,
        @Nullable Long expectedProcessStartEpochMs,
        @Nullable Boolean confirm,
        @Nullable String probeHost,
        @Nullable Integer probePort,
        @Nullable String include,
        @Nullable String exclude) {
}
