package com.nimbly.mcpjavadevtools.server.mcp.tools.failureanalysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.FailureAnalysisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.analyzetrace.AnalyzeTraceRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action.verifyreproduction.FailureLineHitEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureExceptionSection;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisOutcome;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAnalysisResult;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureAttemptEvidence;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result.FailureVerificationDetails;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import org.junit.jupiter.api.Test;

class FailureAnalysisMcpToolTest {

    @Test
    void mapsRuntimeVerificationAndPreservesLineHitEvidence() {
        FailureAnalysisFeature feature = request -> FailureAnalysisResult.verification(
                FailureAnalysisOutcome.REPRODUCED, "ok", new FailureVerificationDetails(
                        FailureFingerprint.expected("ExampleFailure", "ExampleFailure", "example.Order#submit"),
                        FailureFingerprint.expected("ExampleFailure", "ExampleFailure", "example.Order#submit"),
                        new FailureLineHitEvidence("example.Order#submit:42", 2),
                        new FailureAttemptEvidence("capture-1", "matched", null), null, true));
        FailureAnalysisMcpTool tool = new FailureAnalysisMcpTool(feature);

        McpActionResponse response = tool.invokeMcpRequest(new McpActionRequest<>(
                "verify_reproduction",
                new FailureAnalysisMcpActionInput(
                        null, "http://sidecar.example", null, null, null, "capture-1",
                        new FailureAnalysisMcpExpectedFingerprintInput(
                                "ExampleFailure", "ExampleFailure", "example.Order#submit"),
                        new FailureAnalysisMcpLineHitInput("example.Order#submit:42", 2), null)));

        assertThat(response.status()).isEqualTo("reproduced");
        assertThat(response.reasonCode()).isEqualTo("ok");
        assertThat(response.details()).containsEntry("outcome", "REPRODUCED");
        assertThat(response.details()).containsEntry("diagnosisClaimed", true);
        assertThat(response.details().get("lineHit").toString()).contains("hitCount=2");
    }

    @Test
    void mapsTerminalStateWithoutRuntimeFields() {
        FailureAnalysisFeature feature = request -> FailureAnalysisResult.terminal(
                FailureAnalysisOutcome.BLOCKED_MISSING_AUTH,
                "missing_auth",
                new com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result
                        .FailureAttemptEvidence(null, null, 1),
                com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result
                        .FailureCleanupStatus.CLEANUP_CONFIRMED,
                null);
        FailureAnalysisMcpTool tool = new FailureAnalysisMcpTool(feature);

        McpActionResponse response = tool.invokeMcpRequest(new McpActionRequest<>(
                "verify_reproduction",
                new FailureAnalysisMcpActionInput(
                        null, null, null, null, null, null, null, null,
                        new FailureAnalysisMcpTerminalStateInput(
                                "BLOCKED_MISSING_AUTH", "missing_auth", "cleanup_confirmed", 1))));

        assertThat(response.status()).isEqualTo("blocked");
        assertThat(response.reasonCode()).isEqualTo("missing_auth");
        assertThat(response.details()).containsEntry("outcome", "BLOCKED_MISSING_AUTH");
        assertThat(response.details().get("attemptEvidence").toString()).contains("attemptCount=1");
    }

    @Test
    void rejectsInvalidInputDeterministically() {
        FailureAnalysisFeature feature = request -> FailureAnalysisResult.invalidRequest();
        FailureAnalysisMcpTool tool = new FailureAnalysisMcpTool(feature);

        McpActionResponse response = tool.invokeMcpRequest(new McpActionRequest<>(
                "analyze_trace", new FailureAnalysisMcpActionInput(
                        null, null, null, null, null, null, null, null, null)));

        assertThat(response.status()).isEqualTo("inconclusive");
        assertThat(response.reasonCode()).isEqualTo("failure_analysis_request_invalid");
        assertThat(response.details()).containsEntry("diagnosisClaimed", false);
    }

    @Test
    void mapsNullableUntrustedExceptionTypeWithoutBoundaryFailure() {
        FailureAnalysisFeature feature = request -> FailureAnalysisResult.analyzed(
                FailureFingerprint.expected("ExampleFailure", "ExampleFailure", "example.Order#submit"),
                null, null, java.util.List.of(new FailureExceptionSection(null, false, false, java.util.List.of())),
                null, null);
        FailureAnalysisMcpTool tool = new FailureAnalysisMcpTool(feature);

        McpActionResponse response = tool.invokeMcpRequest(new McpActionRequest<>(
                "analyze_trace", new FailureAnalysisMcpActionInput(
                        "trace", "http://sidecar.example", null, null, null, null, null, null, null)));

        assertThat(response.status()).isEqualTo("analyzed");
        assertThat(response.details().get("exceptionSections").toString()).contains("suppressed=false");
        assertThat(response.details().get("exceptionSections").toString()).doesNotContain("internal_error");
    }

    @Test
    void leavesOmittedTimeoutForCorePolicyResolution() {
        FailureAnalysisFeature feature = request -> {
            assertThat(request).isInstanceOf(AnalyzeTraceRequest.class);
            assertThat(((AnalyzeTraceRequest) request).timeout()).isNull();
            return FailureAnalysisResult.invalidRequest();
        };
        FailureAnalysisMcpTool tool = new FailureAnalysisMcpTool(feature);

        tool.invokeMcpRequest(new McpActionRequest<>(
                "analyze_trace", new FailureAnalysisMcpActionInput(
                        "trace", "http://sidecar.example", null, null, null, null, null, null, null)));
    }
}
