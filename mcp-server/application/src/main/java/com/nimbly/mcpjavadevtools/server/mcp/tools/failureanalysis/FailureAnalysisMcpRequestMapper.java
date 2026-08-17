package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.FailureAnalysisAction;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.analyzetrace.AnalyzeTraceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.VerifyReproductionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation.FailureInvestigationContext;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.request.FailureAnalysisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.terminal.FailureTerminalState;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequestMapper;
import java.time.Duration;

/** Maps public MCP fields into action-specific Core request models. */
class FailureAnalysisMcpRequestMapper
        implements McpActionRequestMapper<FailureAnalysisMcpActionInput, FailureAnalysisRequest> {

    @Override
    public FailureAnalysisRequest map(McpActionRequest<FailureAnalysisMcpActionInput> request) {
        if (request == null || request.input() == null) {
            throw new IllegalArgumentException("Failure Analysis request requires action and input");
        }
        FailureAnalysisAction action = FailureAnalysisAction.fromValue(request.action())
                .orElseThrow(() -> new IllegalArgumentException("Failure Analysis action is unsupported"));
        return switch (action) {
            case ANALYZE_TRACE -> analyze(request.input());
            case VERIFY_REPRODUCTION -> verify(request.input());
        };
    }

    private AnalyzeTraceRequest analyze(FailureAnalysisMcpActionInput input) {
        return new AnalyzeTraceRequest(
                required(input.trace(), "trace"), required(input.sidecarBaseUrl(), "sidecarBaseUrl"),
                input.sidecarAuthorization(), investigation(input.investigation()), timeout(input.timeoutMs()));
    }

    private VerifyReproductionRequest verify(FailureAnalysisMcpActionInput input) {
        if (input.terminalState() != null) {
            return new VerifyReproductionRequest(
                    null, null, null, null, null, investigation(input.investigation()), null,
                    terminal(input.terminalState()));
        }
        FailureAnalysisMcpExpectedFingerprintInput expected = input.expectedFingerprint();
        FailureAnalysisMcpLineHitInput lineHit = input.lineHit();
        if (expected == null || lineHit == null) {
            throw new IllegalArgumentException("runtime verification requires fingerprint and lineHit");
        }
        return new VerifyReproductionRequest(
                required(input.captureId(), "captureId"),
                FailureFingerprint.expected(
                        required(expected.exceptionType(), "exceptionType"),
                        required(expected.rootCauseType(), "rootCauseType"),
                        required(expected.nearestApplicationMethodKey(), "nearestApplicationMethodKey")),
                new FailureLineHitEvidence(
                        required(lineHit.strictLineKey(), "strictLineKey"),
                        requiredPositive(lineHit.hitCount(), "hitCount")),
                required(input.sidecarBaseUrl(), "sidecarBaseUrl"), input.sidecarAuthorization(),
                investigation(input.investigation()), timeout(input.timeoutMs()), null);
    }

    private FailureInvestigationContext investigation(FailureAnalysisMcpInvestigationInput input) {
        if (input == null) {
            return null;
        }
        if (input.mode() == null || input.attemptLimit() == null || input.elapsedTimeLimitMs() == null) {
            throw new IllegalArgumentException("investigation requires mode and complete bounds");
        }
        return new FailureInvestigationContext(input.mode(), input.attemptLimit(), input.elapsedTimeLimitMs());
    }

    private FailureTerminalState terminal(FailureAnalysisMcpTerminalStateInput input) {
        if (input.outcome() == null || input.reasonCode() == null || input.cleanupStatus() == null
                || input.attemptCount() == null) {
            throw new IllegalArgumentException("terminalState requires outcome, reasonCode, cleanupStatus, attemptCount");
        }
        return new FailureTerminalState(
                input.outcome(), input.reasonCode(), input.cleanupStatus(), input.attemptCount());
    }

    private Duration timeout(Integer value) {
        if (value == null) {
            return null;
        }
        int milliseconds = value;
        if (milliseconds < 1_000 || milliseconds > 30_000) {
            throw new IllegalArgumentException("timeoutMs must be between 1000 and 30000");
        }
        return Duration.ofMillis(milliseconds);
    }

    private static int requiredPositive(Integer value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String required(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
