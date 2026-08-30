package com.nimbly.mcpjavadevtools.server.core.operation;

/** Canonical runtime-or-terminal reproduction verification arguments. */
public record FailureVerifyArguments(
        String captureId,
        FailureExpectedFingerprintArguments expectedFingerprint,
        FailureLineHitArguments lineHit,
        String sidecarBaseUrl,
        String sidecarAuthorization,
        FailureInvestigationArguments investigation,
        Integer timeoutMs,
        FailureTerminalArguments terminalState) {
}
