package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import org.jspecify.annotations.Nullable;

/** Transport-only canonical Strict Line Key evidence carrier. */
public record FailureAnalysisMcpLineHitInput(
        @Nullable String strictLineKey,
        @Nullable Integer hitCount) {
}
