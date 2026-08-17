package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureExceptionSection;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFrame;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps typed Failure Analysis outcomes into the deterministic MCP envelope. */
class FailureAnalysisMcpResponseMapper
        implements McpActionResponseMapper<FailureAnalysisRequest, FailureAnalysisResult> {

    @Override
    public McpActionResponse map(FailureAnalysisRequest request, FailureAnalysisResult result) {
        Map<String, Object> details = details(result);
        return new McpActionResponse(
                "report", result.outcome().status(), result.reasonCode(), nextActionCode(result),
                nextAction(result), "", reasonMeta(result), null, details);
    }

    @Override
    public McpActionResponse invalidRequest() {
        return map(null, FailureAnalysisResult.invalidRequest());
    }

    @Override
    public McpActionResponse mapBoundary(McpBoundaryFailure failure) {
        return new McpActionResponse(
                "report", "internal_error", "internal_error", "internal_error",
                "Retry the request. If the error persists, provide the correlationId to an operator.",
                "An internal MCP boundary error occurred.",
                Map.of("correlationId", failure.correlationId(), "failedStep", failure.failureKind().value()),
                null, Map.of());
    }

    private Map<String, Object> details(FailureAnalysisResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("outcome", result.outcome().value());
        details.put("reasonCode", result.reasonCode());
        if (result.message() != null) {
            details.put("message", result.message());
        }
        if (result.diagnosisClaimed() != null) {
            details.put("diagnosisClaimed", result.diagnosisClaimed());
        }
        putIfPresent(details, "fingerprint", fingerprint(result.fingerprint(), false));
        putIfPresent(details, "expectedFingerprint", fingerprint(result.expectedFingerprint(), true));
        putIfPresent(details, "observedFingerprint", fingerprint(result.observedFingerprint(), false));
        putIfPresent(details, "lineHit", lineHit(result.lineHit()));
        putIfPresent(details, "attemptEvidence", attemptEvidence(result));
        putIfPresent(details, "cleanupStatus", result.cleanupStatus() == null ? null : result.cleanupStatus().value());
        putIfPresent(details, "investigation", investigation(result.investigation()));
        if (!result.investigationCandidates().isEmpty()) {
            details.put("investigationCandidates", frames(result.investigationCandidates()));
        }
        putIfPresent(details, "dependencyBoundary", frame(result.dependencyBoundary()));
        if (!result.exceptionSections().isEmpty()) {
            details.put("exceptionSections", sections(result.exceptionSections()));
        }
        if (!result.incompleteReasons().isEmpty()) {
            details.put("incompleteReasons", result.incompleteReasons());
        }
        putIfPresent(details, "httpStatus", result.httpStatus());
        return details;
    }

    private Map<String, Object> reasonMeta(FailureAnalysisResult result) {
        if (result.httpStatus() == null) {
            return Map.of();
        }
        return Map.of("httpStatus", result.httpStatus());
    }

    private String nextActionCode(FailureAnalysisResult result) {
        return switch (result.outcome()) {
            case ANALYZED -> "verify_runtime_reproduction";
            case REPRODUCED -> "report_diagnosis";
            case NOT_REPRODUCED -> "review_trigger_or_fingerprint";
            case INCONCLUSIVE -> "inspect_failure_evidence";
            default -> "resolve_failure_analysis_blocker";
        };
    }

    private String nextAction(FailureAnalysisResult result) {
        return switch (result.outcome()) {
            case ANALYZED -> "Use Probe and the supported trigger, then verify reproduction.";
            case REPRODUCED -> "A diagnosis may be reported from the matching evidence.";
            case NOT_REPRODUCED -> "Review the bounded trigger and fingerprint evidence.";
            case INCONCLUSIVE -> "Provide complete bounded evidence before retrying.";
            default -> "Resolve the bounded Failure Analysis blocker before retrying.";
        };
    }

    private Map<String, Object> fingerprint(FailureFingerprint value, boolean expected) {
        if (value == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exceptionType", value.exceptionType());
        result.put("rootCauseType", value.rootCauseType());
        if (value.nearestApplicationMethodKey() != null) {
            result.put("nearestApplicationMethodKey", value.nearestApplicationMethodKey());
        }
        if (!expected && value.nearestApplicationFrame() != null) {
            result.put("nearestApplicationFrame", frame(value.nearestApplicationFrame()));
        }
        if (value.normalizedMessage() != null) {
            result.put("normalizedMessage", value.normalizedMessage());
        }
        result.put("complete", value.complete());
        if (!value.incompletenessReasons().isEmpty()) {
            result.put("incompletenessReasons", value.incompletenessReasons());
        }
        return result;
    }

    private Map<String, Object> lineHit(FailureLineHitEvidence value) {
        if (value == null) {
            return null;
        }
        return Map.of("strictLineKey", value.strictLineKey(), "hitCount", value.hitCount());
    }

    private Map<String, Object> attemptEvidence(FailureAnalysisResult result) {
        if (result.attemptEvidence() == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (result.attemptEvidence().captureId() != null) {
            values.put("captureId", result.attemptEvidence().captureId());
        }
        if (result.attemptEvidence().sidecarOutcome() != null) {
            values.put("sidecarOutcome", result.attemptEvidence().sidecarOutcome());
        }
        if (result.attemptEvidence().attemptCount() != null) {
            values.put("attemptCount", result.attemptEvidence().attemptCount());
        }
        return values;
    }

    private Map<String, Object> investigation(FailureInvestigationContext value) {
        if (value == null) {
            return null;
        }
        return Map.of(
                "mode", value.mode(),
                "attemptLimit", value.attemptLimit(),
                "elapsedTimeLimitMs", value.elapsedTimeLimitMs());
    }

    private List<Map<String, Object>> frames(List<FailureFrame> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FailureFrame value : values) {
            result.add(frame(value));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> frame(FailureFrame value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", value.className());
        result.put("methodName", value.methodName());
        result.put("sourceFile", value.sourceFile());
        result.put("lineNumber", value.lineNumber());
        result.put("ownership", value.ownership());
        result.put("codeSource", value.codeSource());
        result.put("strictLineKey", value.strictLineKey());
        result.put("methodDescriptor", value.methodDescriptor());
        result.put("codeSourceCandidates", value.codeSourceCandidates());
        result.put("resolutionReason", value.resolutionReason());
        return result;
    }

    private List<Map<String, Object>> sections(List<FailureExceptionSection> values) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FailureExceptionSection value : values) {
            Map<String, Object> section = new LinkedHashMap<>();
            putIfPresent(section, "exceptionType", value.exceptionType());
            section.put("suppressed", value.suppressed());
            section.put("elidedFrames", value.elidedFrames());
            section.put("frames", frames(value.frames()));
            result.add(section);
        }
        return List.copyOf(result);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
