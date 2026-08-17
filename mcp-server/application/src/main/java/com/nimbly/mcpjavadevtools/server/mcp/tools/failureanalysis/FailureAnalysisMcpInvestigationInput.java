package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import org.jspecify.annotations.Nullable;

/** Transport-only investigation context carrier. */
public record FailureAnalysisMcpInvestigationInput(
        @Nullable String mode,
        @Nullable Integer attemptLimit,
        @Nullable Integer elapsedTimeLimitMs) {
}
