package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import org.jspecify.annotations.Nullable;

/** Transport-only terminal workflow state carrier. */
public record FailureAnalysisMcpTerminalStateInput(
        @Nullable String outcome,
        @Nullable String reasonCode,
        @Nullable String cleanupStatus,
        @Nullable Integer attemptCount) {
}
