package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.operation;

/** Canonical analyze-trace arguments with transport timeout expressed in milliseconds. */
public record FailureAnalyzeArguments(
        String trace,
        String sidecarBaseUrl,
        String sidecarAuthorization,
        FailureInvestigationArguments investigation,
        Integer timeoutMs) {
}
