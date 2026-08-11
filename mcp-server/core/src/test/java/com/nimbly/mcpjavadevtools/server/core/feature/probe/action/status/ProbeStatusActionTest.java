package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeBatchStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeSingleStatusRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeyBatchSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProbeStatusActionTest {

    @Test
    void returnsCompactSafeSingleStatusInformationForAConfirmedLineHit() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                """
                {
                  "probe":{"key":"example.Probe#run:12","hitCount":3,"lastHitEpoch":99,"lineResolvable":true},
                  "capturePreview":{"available":true,"captureId":"capture-1","executionPaths":["path-1"]},
                  "runtime":{"mode":"observe","serverEpoch":100,"applicationType":{"value":"secret"}}
                }
                """,
                configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeSingleStatusRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                new ProbeKeySelector("example.Probe#run:12", null),
                Duration.ofSeconds(3)));

        ProbeStatusResult status = (ProbeStatusResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(status.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.lineHit()).isTrue();
            assertThat(entry.capturePreview().executionPaths()).isEmpty();
            assertThat(entry.runtime().mode()).isEqualTo("observe");
        });
    }

    @Test
    void failsClosedWhenTheSingleResponseCannotBeAssociatedWithTheRequestedKey() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"probe\":{\"key\":\"example.Other#run:12\",\"hitCount\":1}}",
                configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeSingleStatusRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                new ProbeKeySelector("example.Probe#run:12", null),
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.STATUS_FAILED);
        assertThat(result.reasonMetadata().failedStep().value()).isEqualTo("probe_endpoint_response");
    }

    @Test
    void preservesRequestedBatchOrderWhenTheSidecarResponseOrderDiffers() {
        ProbeEndpointConfiguration configuration = configuration();
        AtomicInteger invocations = new AtomicInteger();
        ProbeEndpointClient client = request -> {
            invocations.incrementAndGet();
            return new ProbeEndpointResponse(
                    200,
                    Map.of(),
                    """
                    {
                      "results":[
                        {"ok":true,"probe":{"key":"example.B#run:22","hitCount":2,"lineResolvable":true}},
                        {"ok":true,"probe":{"key":"example.A#run:11","hitCount":1,"lineResolvable":true}}
                      ]
                    }
                    """,
                    configuration);
        };

        ProbeResult result = action(configuration, client).execute(new ProbeBatchStatusRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                new ProbeKeyBatchSelector(List.of("example.A#run:11", "example.B#run:22")),
                null));

        ProbeStatusResult status = (ProbeStatusResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(invocations).hasValue(1);
        assertThat(status.entries()).extracting(entry -> entry.requestedKey())
                .containsExactly("example.A#run:11", "example.B#run:22");
    }

    @Test
    void rejectsAnInvalidStrictLineKeyWithoutCallingTheEndpoint() {
        ProbeEndpointConfiguration configuration = configuration();
        AtomicInteger invocations = new AtomicInteger();
        ProbeEndpointClient client = request -> {
            invocations.incrementAndGet();
            return new ProbeEndpointResponse(200, Map.of(), "{}", configuration);
        };

        ProbeResult result = action(configuration, client).execute(new ProbeSingleStatusRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                new ProbeKeySelector("example.Probe#run", null),
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.LINE_KEY_REQUIRED);
        assertThat(invocations).hasValue(0);
    }

    @Test
    void returnsInvalidLineTargetWithoutClaimingALineHit() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"probe\":{\"key\":\"example.Probe#run:12\",\"hitCount\":8,\"lineResolvable\":false,\"lineValidation\":\"invalid_line_target\"}}",
                configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeSingleStatusRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                new ProbeKeySelector("example.Probe#run:12", null),
                null));

        ProbeStatusResult status = (ProbeStatusResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.INVALID_LINE_TARGET);
        assertThat(status.entries()).singleElement().satisfies(entry -> assertThat(entry.lineHit()).isFalse());
    }

    private ProbeStatusAction action(ProbeEndpointConfiguration configuration, ProbeEndpointClient client) {
        return new ProbeStatusAction(
                new ProbeTargetResolver(configuration, null),
                configuration,
                client,
                new ProbeResponseCompactionPolicy(false, 64, 4, 8, 64, Set.of("content-type")));
    }

    private ProbeEndpointConfiguration configuration() {
        ProbeRequestBounds bounds = new ProbeRequestBounds(
                Duration.ofSeconds(1),
                Duration.ofSeconds(60),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                1,
                10);
        return new ProbeEndpointConfiguration(
                null,
                new ProbeEndpointPaths(
                        "/__probe/status",
                        "/__probe/reset",
                        "/__probe/actuate",
                        "/__probe/capture",
                        "/__probe/profiler"),
                new ProbeRequestPolicy(Duration.ofSeconds(15), Duration.ofMillis(500), 1, false, 3, bounds),
                new ProbeEndpointLimits(64, 128, 4096, 65536, 1048576));
    }
}
