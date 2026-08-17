package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import org.jspecify.annotations.Nullable;

/** Transport-only expected fingerprint carrier. */
public record FailureAnalysisMcpExpectedFingerprintInput(
        @Nullable String exceptionType,
        @Nullable String rootCauseType,
        @Nullable String nearestApplicationMethodKey) {
}
