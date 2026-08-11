package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture.impl.ProbeCaptureAction;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbeCaptureActionTest {

    @Test
    void returnsBoundedMetadataWithoutCaptureValues() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                """
                {"capture":{"captureId":"capture-1","methodKey":"example.Work#doIt",
                "capturedAtEpoch":1,"args":[{"value":"secret"}],"returnValue":{"value":"hidden"},
                "truncatedAny":false,"executionPaths":["a","b","c"]}}
                """,
                configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeCaptureRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                "capture-1",
                null));

        ProbeCaptureResult capture = (ProbeCaptureResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(capture.capture().argsCount()).isEqualTo(1);
        assertThat(capture.capture().hasReturnValue()).isTrue();
        assertThat(capture.capture().executionPaths()).containsExactly("a", "b");
    }

    @Test
    void failsClosedWhenSuccessfulResponseDoesNotMatchTheRequestedCapture() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"capture\":{\"captureId\":\"other\"}}",
                configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeCaptureRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                "capture-1",
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.CAPTURE_FAILED);
        assertThat(((ProbeCaptureResult) result.actionResult().orElseThrow()).reason()).isEqualTo("malformed_response");
    }

    private ProbeCaptureAction action(ProbeEndpointConfiguration configuration, ProbeEndpointClient client) {
        return new ProbeCaptureAction(
                new ProbeTargetResolver(configuration, null),
                configuration,
                client,
                new ProbeResponseCompactionPolicy(true, 64, 2, 8, 64, Set.of("content-type")));
    }

    private ProbeEndpointConfiguration configuration() {
        ProbeRequestBounds bounds = new ProbeRequestBounds(
                Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofMillis(100), Duration.ofSeconds(5), 1, 10);
        return new ProbeEndpointConfiguration(
                null,
                new ProbeEndpointPaths("/__probe/status", "/__probe/reset", "/__probe/actuate", "/__probe/capture", "/__probe/profiler"),
                new ProbeRequestPolicy(Duration.ofSeconds(15), Duration.ofMillis(500), 1, false, 3, bounds),
                new ProbeEndpointLimits(64, 128, 4096, 65536, 1048576));
    }
}
