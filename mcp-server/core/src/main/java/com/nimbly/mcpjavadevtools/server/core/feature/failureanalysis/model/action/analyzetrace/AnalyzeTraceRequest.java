package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.analyzetrace;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import java.time.Duration;

/** Typed analyze_trace request after Application boundary mapping. */
public record AnalyzeTraceRequest(
        String trace,
        String sidecarBaseUrl,
        String sidecarAuthorization,
        FailureInvestigationContext investigation,
        Duration timeout) implements FailureAnalysisRequest {

    public AnalyzeTraceRequest {
        trace = required(trace, "trace");
        sidecarBaseUrl = required(sidecarBaseUrl, "sidecarBaseUrl");
        sidecarAuthorization = optionalAuthorization(sidecarAuthorization);
    }

    @Override
    public FailureAnalysisAction action() {
        return FailureAnalysisAction.ANALYZE_TRACE;
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
