package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.action.FailureAnalysisActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClient;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.endpoint.FailureEvidenceResponseMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.fingerprint.FailureFingerprintComparator;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.VerifyReproductionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureEvidenceResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureVerificationEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint.FailureVerifyEvidenceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAttemptEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureCleanupStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureVerificationDetails;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.terminal.FailureTerminalState;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.policy.FailureAnalysisPolicy;

/** Real runtime and terminal-state verify_reproduction action. */
public final class VerifyReproductionAction implements FailureAnalysisActionHandler {

    private final FailureEvidenceClient client;
    private final FailureEvidenceResponseMapper responseMapper;
    private final FailureAnalysisPolicy policy;

    public VerifyReproductionAction(
            FailureEvidenceClient client,
            FailureEvidenceResponseMapper responseMapper,
            FailureAnalysisPolicy policy) {
        this.client = client;
        this.responseMapper = responseMapper;
        this.policy = policy;
    }

    @Override
    public FailureAnalysisAction action() {
        return FailureAnalysisAction.VERIFY_REPRODUCTION;
    }

    @Override
    public FailureAnalysisResult execute(FailureAnalysisRequest input) {
        if (!(input instanceof VerifyReproductionRequest request)) {
            return FailureAnalysisResult.invalidRequest();
        }
        if (request.terminalState() != null) {
            return terminal(request);
        }
        return runtime(request);
    }

    private FailureAnalysisResult runtime(VerifyReproductionRequest request) {
        try {
            FailureEvidenceResponse response = client.verify(new FailureVerifyEvidenceRequest(
                    request.sidecarBaseUrl(), request.captureId(), request.expectedFingerprint().exceptionType(),
                    request.expectedFingerprint().rootCauseType(),
                    request.expectedFingerprint().nearestApplicationMethodKey(),
                    request.sidecarAuthorization(), policy.timeoutOrDefault(request.timeout())));
            return result(response, request);
        } catch (FailureEvidenceClientException exception) {
            if (exception.failureKind() == FailureEvidenceFailureKind.INTERRUPTED) {
                Thread.currentThread().interrupt();
            }
            return FailureAnalysisResult.blockedVerification(null, request.investigation());
        } catch (RuntimeException exception) {
            return FailureAnalysisResult.blockedVerification(null, request.investigation());
        }
    }

    private FailureAnalysisResult result(
            FailureEvidenceResponse response, VerifyReproductionRequest request) {
        if (response.status() == 404) {
            return FailureAnalysisResult.captureNotFound(request.captureId(), request.investigation());
        }
        if (response.status() < 200 || response.status() >= 300 || response.payload() == null) {
            return FailureAnalysisResult.blockedVerification(response.status(), request.investigation());
        }
        FailureVerificationEvidence evidence = responseMapper.verify(response.payload());
        if (evidence.outcome() == null) {
            return FailureAnalysisResult.invalidVerification(request.investigation());
        }
        FailureAttemptEvidence attempts = new FailureAttemptEvidence(
                request.captureId(), evidence.outcome(), null);
        String mismatch = FailureFingerprintComparator.mismatchReason(
                request.expectedFingerprint(), evidence.observedFingerprint());
        if ("matched".equals(evidence.outcome()) && mismatch == null) {
            return FailureAnalysisResult.verification(
                    FailureAnalysisOutcome.REPRODUCED, "ok", new FailureVerificationDetails(
                            request.expectedFingerprint(), evidence.observedFingerprint(), request.lineHit(),
                            attempts, request.investigation(), true));
        }
        String reason = "matched".equals(evidence.outcome()) && mismatch != null
                ? mismatch : evidence.outcome();
        return FailureAnalysisResult.verification(
                FailureAnalysisOutcome.NOT_REPRODUCED, reason, new FailureVerificationDetails(
                        request.expectedFingerprint(), evidence.observedFingerprint(), request.lineHit(),
                        attempts, request.investigation(), false));
    }

    private FailureAnalysisResult terminal(VerifyReproductionRequest request) {
        FailureTerminalState state = request.terminalState();
        return FailureAnalysisResult.terminal(
                terminalOutcome(state.outcome()), state.reasonCode(),
                new FailureAttemptEvidence(null, null, state.attemptCount()),
                cleanupStatus(state.cleanupStatus()), request.investigation());
    }

    private static FailureAnalysisOutcome terminalOutcome(String value) {
        for (FailureAnalysisOutcome outcome : FailureAnalysisOutcome.values()) {
            if (outcome.value().equals(value)) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("unsupported terminal outcome");
    }

    private static FailureCleanupStatus cleanupStatus(String value) {
        return switch (value) {
            case "cleanup_confirmed" -> FailureCleanupStatus.CLEANUP_CONFIRMED;
            case "cleanup_incomplete" -> FailureCleanupStatus.CLEANUP_INCOMPLETE;
            case "external_workflow_owned" -> FailureCleanupStatus.EXTERNAL_WORKFLOW_OWNED;
            default -> throw new IllegalArgumentException("unsupported cleanup status");
        };
    }
}
