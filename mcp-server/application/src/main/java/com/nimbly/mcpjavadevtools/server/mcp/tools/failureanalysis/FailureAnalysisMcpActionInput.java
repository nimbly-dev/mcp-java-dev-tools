package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import org.jspecify.annotations.Nullable;

/** Superset transport carrier mapped into one of the two typed Core requests. */
public record FailureAnalysisMcpActionInput(
        @Nullable String trace,
        @Nullable String sidecarBaseUrl,
        @Nullable String sidecarAuthorization,
        @Nullable FailureAnalysisMcpInvestigationInput investigation,
        @Nullable Integer timeoutMs,
        @Nullable String captureId,
        @Nullable FailureAnalysisMcpExpectedFingerprintInput expectedFingerprint,
        @Nullable FailureAnalysisMcpLineHitInput lineHit,
        @Nullable FailureAnalysisMcpTerminalStateInput terminalState) {
}
