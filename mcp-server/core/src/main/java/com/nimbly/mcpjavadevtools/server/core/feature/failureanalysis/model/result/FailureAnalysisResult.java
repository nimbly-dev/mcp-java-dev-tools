package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureExceptionSection;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFrame;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import java.util.List;

/** Complete bounded deterministic output from one Failure Analysis action. */
public record FailureAnalysisResult(
        FailureAnalysisOutcome outcome,
        String reasonCode,
        String message,
        Boolean diagnosisClaimed,
        FailureFingerprint fingerprint,
        FailureFingerprint expectedFingerprint,
        FailureFingerprint observedFingerprint,
        FailureLineHitEvidence lineHit,
        FailureAttemptEvidence attemptEvidence,
        FailureCleanupStatus cleanupStatus,
        FailureInvestigationContext investigation,
        List<FailureFrame> investigationCandidates,
        FailureFrame dependencyBoundary,
        List<FailureExceptionSection> exceptionSections,
        List<String> incompleteReasons,
        Integer httpStatus) {

    public FailureAnalysisResult {
        investigationCandidates = investigationCandidates == null ? List.of() : List.copyOf(investigationCandidates);
        exceptionSections = exceptionSections == null ? List.of() : List.copyOf(exceptionSections);
        incompleteReasons = incompleteReasons == null ? List.of() : List.copyOf(incompleteReasons);
    }

    /** @return canonical invalid boundary outcome */
    public static FailureAnalysisResult invalidRequest() {
        return new FailureAnalysisResult(
                FailureAnalysisOutcome.INCONCLUSIVE,
                "failure_analysis_request_invalid",
                "Failure Analysis request is invalid.",
                false, null, null, null, null, null, null, null, List.of(), null, List.of(), List.of(), null);
    }

    /** @return blocked Sidecar analyze outcome */
    public static FailureAnalysisResult blockedAnalyze(Integer status) {
        return new FailureAnalysisResult(
                FailureAnalysisOutcome.BLOCKED_SIDECAR_UNAVAILABLE,
                "sidecar_failure_analysis_unavailable",
                "Failure analysis is blocked because the Sidecar is unavailable or rejected the trace.",
                null, null, null, null, null, null, null, null, List.of(), null, List.of(), List.of(), status);
    }

    /** @return bounded inconclusive trace result */
    public static FailureAnalysisResult incompleteTrace(
            FailureFingerprint fingerprint,
            List<FailureFrame> candidates,
            FailureFrame boundary,
            List<FailureExceptionSection> sections,
            List<String> reasons,
            FailureInvestigationContext investigation) {
        return new FailureAnalysisResult(
                FailureAnalysisOutcome.INCONCLUSIVE,
                "failure_fingerprint_incomplete",
                "Failure fingerprint is incomplete. No diagnosis is claimed and runtime verification is blocked.",
                false, fingerprint, null, null, null, null, null, investigation,
                candidates, boundary, sections, reasons, null);
    }

    /** @return analyzed trace result without a diagnosis claim */
    public static FailureAnalysisResult analyzed(
            FailureFingerprint fingerprint,
            List<FailureFrame> candidates,
            FailureFrame boundary,
            List<FailureExceptionSection> sections,
            List<String> reasons,
            FailureInvestigationContext investigation) {
        return new FailureAnalysisResult(
                FailureAnalysisOutcome.ANALYZED, "ok",
                "Failure fingerprint prepared. No diagnosis is claimed until runtime reproduction matches.",
                false, fingerprint, null, null, null, null, null, investigation,
                candidates, boundary, sections, reasons, null);
    }

    /** @return terminal verification outcome with no runtime call */
    public static FailureAnalysisResult terminal(
            FailureAnalysisOutcome outcome,
            String reasonCode,
            FailureAttemptEvidence attempts,
            FailureCleanupStatus cleanupStatus,
            FailureInvestigationContext investigation) {
        return new FailureAnalysisResult(
                outcome, reasonCode,
                "Failure investigation ended without runtime reproduction. No diagnosis is claimed.",
                false, null, null, null, null, attempts, cleanupStatus, investigation,
                List.of(), null, List.of(), List.of(), null);
    }

    /** @return blocked runtime verification result */
    public static FailureAnalysisResult blockedVerification(
            Integer status,
            FailureInvestigationContext investigation) {
        return new FailureAnalysisResult(
                FailureAnalysisOutcome.BLOCKED_SIDECAR_UNAVAILABLE,
                "sidecar_failure_verification_unavailable",
                "Failure verification is blocked because the Sidecar is unavailable.",
                false, null, null, null, null, null, null, investigation,
                List.of(), null, List.of(), List.of(), status);
    }

    /** @return expired or missing capture result */
    public static FailureAnalysisResult captureNotFound(
            String captureId,
            FailureInvestigationContext investigation) {
        return new FailureAnalysisResult(
                FailureAnalysisOutcome.INCONCLUSIVE,
                "capture_not_found",
                "Failure verification is inconclusive because the capture is unavailable.",
                false, null, null, null, null,
                new FailureAttemptEvidence(captureId, null, null), null, investigation,
                List.of(), null, List.of(), List.of(), null);
    }

    /** @return malformed or incomplete verification response */
    public static FailureAnalysisResult invalidVerification(
            FailureInvestigationContext investigation) {
        return new FailureAnalysisResult(
                FailureAnalysisOutcome.INCONCLUSIVE,
                "failure_verification_invalid",
                "Failure verification is inconclusive because runtime evidence is incomplete.",
                false, null, null, null, null, null, null, investigation,
                List.of(), null, List.of(), List.of(), null);
    }

    /** @return normalized runtime verification outcome */
    public static FailureAnalysisResult verification(
            FailureAnalysisOutcome outcome,
            String reasonCode,
            FailureVerificationDetails details) {
        FailureCleanupStatus cleanup = FailureCleanupStatus.EXTERNAL_WORKFLOW_OWNED;
        return new FailureAnalysisResult(
                outcome, reasonCode, verificationMessage(outcome), details.diagnosisClaimed(),
                null, details.expectedFingerprint(), details.observedFingerprint(), details.lineHit(),
                details.attemptEvidence(), cleanup, details.investigation(),
                List.of(), null, List.of(), List.of(), null);
    }

    private static String verificationMessage(FailureAnalysisOutcome outcome) {
        if (outcome == FailureAnalysisOutcome.REPRODUCED) {
            return "Failure reproduced with matching runtime fingerprint and Strict Line Key evidence.";
        }
        return "Failure was not reproduced. No diagnosis is claimed.";
    }
}
