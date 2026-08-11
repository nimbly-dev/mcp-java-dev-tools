package com.nimbly.mcpjavadevtools.server.mcp.tools.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRecord;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeMcpToolTest {

    @Test
    void mapsCaptureMcpInputThroughTheIntentionalCoreFeatureBoundary() {
        ProbeFeature feature = request -> {
            assertThat(request).isInstanceOf(ProbeCaptureRequest.class);
            ProbeCaptureRequest capture = (ProbeCaptureRequest) request;
            assertThat(capture.captureId()).isEqualTo("capture-1");
            return ProbeResult.success(new ProbeCaptureResult(
                    true,
                    new ProbeCaptureRecord("capture-1", "example.Work#doIt", null, null, null, null, null, null, 0, false, false, false, List.of()),
                    null));
        };

        McpActionResponse response = new ProbeMcpTool(feature).invokeMcpRequest(new McpActionRequest<>(
                "capture",
                input("capture-1")));

        assertThat(response.reasonCode()).isEqualTo("success");
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.nextActionCode()).isNull();
        assertThat(response.result()).isInstanceOf(ProbeCaptureResult.class);
        assertThat(response.resultType()).isEqualTo("report");
        assertThat(response.details()).containsKey("request");
    }

    @Test
    void mapsStructuralInputFailureWithoutInvokingTheCoreFeature() {
        ProbeFeature feature = request -> {
            throw new AssertionError("Core Feature must not receive invalid MCP input");
        };

        McpActionResponse response = new ProbeMcpTool(feature).invokeMcpRequest(new McpActionRequest<>("unknown", input("capture-1")));

        assertThat(response.reasonCode()).isEqualTo("invalid_request");
        assertThat(response.status()).isEqualTo("invalid_request");
        assertThat(response.nextActionCode()).isEqualTo("invalid_request");
        assertThat(response.result()).isNull();
    }

    @Test
    void containsUnexpectedFeatureFailuresWithoutLeakingDetails() {
        ProbeFeature feature = request -> {
            throw new IllegalStateException("Authorization: Bearer secret-value");
        };

        McpActionResponse response = new ProbeMcpTool(feature).invokeMcpRequest(new McpActionRequest<>("capture", input("capture-1")));

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reason()).doesNotContain("secret-value");
        assertThat(response.reasonMeta().toString()).doesNotContain("secret-value");
    }

    @Test
    void preservesExpectedCoreProbeFailures() {
        ProbeFeature feature = request -> ProbeResult.failure(ProbeReasonCode.PROBE_UNREACHABLE, ProbeReasonMetadata.status());

        McpActionResponse response = new ProbeMcpTool(feature).invokeMcpRequest(new McpActionRequest<>("capture", input("capture-1")));

        assertThat(response.reasonCode()).isEqualTo("probe_unreachable");
        assertThat(response.status()).isEqualTo("probe_unreachable");
        assertThat(response.nextActionCode()).isEqualTo("verify_probe_connectivity");
    }

    @Test
    void mapsEndpointInterruptionToTheDeterministicInterruptedOutcome() {
        ProbeFeature feature = request -> {
            Thread.currentThread().interrupt();
            return ProbeResult.blocked(ProbeReasonCode.WAIT_INTERRUPTED, ProbeReasonMetadata.status());
        };

        McpActionResponse response = new ProbeMcpTool(feature).invokeMcpRequest(new McpActionRequest<>(
                "capture",
                input("capture-1")));

        assertThat(response.status()).isEqualTo("interrupted");
        assertThat(response.reasonCode()).isEqualTo("interrupted");
        assertThat(response.nextActionCode()).isEqualTo("interrupted");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
    }

    @Test
    void containsUnexpectedRequestMappingFailures() {
        ProbeMcpRequestMapper mapper = new ProbeMcpRequestMapper() {
            @Override
            public ProbeRequest map(McpActionRequest<ProbeMcpActionInput> request) {
                throw new IllegalStateException("Authorization: Bearer secret-value");
            }
        };

        McpActionResponse response = new ProbeMcpTool(
                request -> ProbeResult.success(),
                mapper,
                new ProbeMcpResponseMapper(),
                new McpBoundaryExecutor()).invokeMcpRequest(new McpActionRequest<>(
                        "capture",
                        input("capture-1")));

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta()).containsEntry("failedStep", McpBoundaryFailureKind.REQUEST_MAPPING.value());
        assertThat(response.reason()).doesNotContain("secret-value");
    }

    @Test
    void containsUnexpectedResponseMappingFailures() {
        ProbeMcpResponseMapper mapper = new ProbeMcpResponseMapper() {
            @Override
            public McpActionResponse map(ProbeRequest request, ProbeResult result) {
                throw new IllegalStateException("Authorization: Bearer secret-value");
            }
        };

        McpActionResponse response = new ProbeMcpTool(
                request -> ProbeResult.success(),
                new ProbeMcpRequestMapper(),
                mapper,
                new McpBoundaryExecutor()).invokeMcpRequest(new McpActionRequest<>(
                        "capture",
                        input("capture-1")));

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta()).containsEntry("failedStep", McpBoundaryFailureKind.RESPONSE_MAPPING.value());
        assertThat(response.reason()).doesNotContain("secret-value");
    }

    private ProbeMcpActionInput input(String captureId) {
        return new ProbeMcpActionInput(
                null, null, null, null, null, null, null, null, null, null, captureId,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
