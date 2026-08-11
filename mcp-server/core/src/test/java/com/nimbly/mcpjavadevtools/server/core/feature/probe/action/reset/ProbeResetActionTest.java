package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.reset;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.reset.impl.ProbeResetAction;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeClassResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeSingleResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key.ProbeKeySelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbeResetActionTest {

    @Test
    void resetsOneStrictLineKeyThroughTheExistingSidecarContract() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"ok\":true,\"key\":\"example.Probe#run:12\",\"lineResolvable\":true}",
                configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeSingleResetRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                new ProbeKeySelector("example.Probe#run:12", null),
                null));

        ProbeResetResult reset = (ProbeResetResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(reset.entries()).singleElement().satisfies(entry -> assertThat(entry.reset()).isTrue());
    }

    @Test
    void returnsDeterministicFailureForAnUnknownClassScopedReset() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"ok\":true,\"selector\":\"className\",\"className\":\"example.Missing\",\"count\":0,\"results\":[],\"reason\":\"class_not_found\"}",
                configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeClassResetRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                "example.Missing",
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.RESET_FAILED);
    }

    @Test
    void failsClosedForAMalformedSuccessfulClassScopedResponse() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(200, Map.of(), "{}", configuration);

        ProbeResult result = action(configuration, client).execute(new ProbeClassResetRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                "example.Missing",
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.RESET_FAILED);
    }

    @Test
    void rejectsAnInvalidClassNameBeforeItIsSerializedIntoJson() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> {
            throw new AssertionError("endpoint must not be invoked");
        };

        ProbeResult result = action(configuration, client).execute(new ProbeClassResetRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                "example.Valid\"},\"keys\":[\"injected",
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.INVALID_REQUEST);
    }

    private ProbeResetAction action(ProbeEndpointConfiguration configuration, ProbeEndpointClient client) {
        return new ProbeResetAction(
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
