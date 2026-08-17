package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.terminal.FailureTerminalState;
import java.time.Duration;
import java.util.Objects;

/** Typed runtime-evidence or terminal-state verify_reproduction request. */
public record VerifyReproductionRequest(
        String captureId,
        FailureFingerprint expectedFingerprint,
        FailureLineHitEvidence lineHit,
        String sidecarBaseUrl,
        String sidecarAuthorization,
        FailureInvestigationContext investigation,
        Duration timeout,
        FailureTerminalState terminalState) implements FailureAnalysisRequest {

    public VerifyReproductionRequest {
        sidecarAuthorization = optionalAuthorization(sidecarAuthorization);
        if (terminalState == null) {
            captureId = required(captureId, "captureId");
            expectedFingerprint = Objects.requireNonNull(expectedFingerprint, "expectedFingerprint must not be null");
            lineHit = Objects.requireNonNull(lineHit, "lineHit must not be null");
            sidecarBaseUrl = required(sidecarBaseUrl, "sidecarBaseUrl");
        } else if (captureId != null || expectedFingerprint != null || lineHit != null || sidecarBaseUrl != null
                || sidecarAuthorization != null || timeout != null) {
            throw new IllegalArgumentException("terminal verification cannot include runtime fields");
        }
    }

    @Override
    public FailureAnalysisAction action() {
        return FailureAnalysisAction.VERIFY_REPRODUCTION;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalAuthorization(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > 8_192) {
            throw new IllegalArgumentException("sidecarAuthorization must contain 1 to 8192 characters");
        }
        return normalized;
    }
}
