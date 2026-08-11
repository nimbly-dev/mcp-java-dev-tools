package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.impl.ProbeCheckAction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointStatus;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProbeCheckActionTest {

    @Test
    void runsBoundedResetAndStatusDiagnosticsAndRedactsResponseCredentials() {
        ProbeEndpointConfiguration configuration = configuration();
        List<ProbeEndpointRequest> requests = new ArrayList<>();
        ProbeEndpointClient client = request -> responseForCheck(requests, request, configuration);
        ProbeResult result = action(configuration, client).execute(new ProbeCheckRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                Map.of("Authorization", "Bearer request-token"),
                Duration.ofSeconds(3)));

        ProbeCheckResult check = (ProbeCheckResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(check.healthy()).isTrue();
        assertThat(check.status().runtime().appPort().value()).isEqualTo(8080);
        assertThat(check.status().diagnosticHeaders()).containsEntry("Authorization", "[REDACTED]");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).method()).isEqualTo("POST");
        assertThat(requests.get(0).headers()).containsEntry("Authorization", "Bearer request-token");
        assertThat(requests.get(1).method()).isEqualTo("GET");
        assertThat(requests.get(1).endpoint().getRawQuery()).isEqualTo("key=mcp.jvm.diagnose%23key");
        assertThat(requests.get(1).headers()).containsEntry("Authorization", "Bearer request-token");
    }

    @Test
    void returnsSafeEndpointClassificationsWhenProtectedOrUnreachable() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> {
            if (request.endpoint().getPath().endsWith("reset")) {
                return new ProbeEndpointResponse(401, Map.of(), "{\"error\":\"unauthorized\"}", configuration);
            }
            throw new ProbeEndpointClientException(
                    ProbeEndpointFailureKind.UNREACHABLE,
                    "secret endpoint detail");
        };

        ProbeResult result = action(configuration, client).execute(new ProbeCheckRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                Map.of(),
                null));

        ProbeCheckResult check = (ProbeCheckResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.DIAGNOSE_FAILED);
        assertThat(check.reset().status()).isEqualTo(ProbeCheckEndpointStatus.UNAUTHORIZED);
        assertThat(check.status().status()).isEqualTo(ProbeCheckEndpointStatus.UNREACHABLE);
        assertThat(check.recommendations()).noneMatch(value -> value.contains("secret endpoint detail"));
    }

    @Test
    void rejectsUnsafeProtectedEndpointHeadersBeforeEndpointInvocation() {
        ProbeEndpointConfiguration configuration = configuration();
        List<ProbeEndpointRequest> requests = new ArrayList<>();
        ProbeEndpointClient client = request -> {
            requests.add(request);
            return new ProbeEndpointResponse(200, Map.of(), "{}", configuration);
        };

        ProbeResult result = action(configuration, client).execute(new ProbeCheckRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                Map.of("X-Test\r\nInjected", "value"),
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.INVALID_REQUEST);
        assertThat(requests).isEmpty();
    }

    @Test
    void doesNotNormalizeUnexpectedEndpointProgrammingFailures() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> {
            throw new IllegalStateException("endpoint collaborator defect");
        };

        assertThatThrownBy(() -> action(configuration, client).execute(new ProbeCheckRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                Map.of(),
                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("endpoint collaborator defect");
    }

    private ProbeEndpointResponse responseForCheck(
            List<ProbeEndpointRequest> requests,
            ProbeEndpointRequest request,
            ProbeEndpointConfiguration configuration) {
        requests.add(request);
        if (request.endpoint().getPath().endsWith("reset")) {
            return new ProbeEndpointResponse(200, Map.of("Content-Type", "application/json"), "{\"ok\":true}", configuration);
        }
        return new ProbeEndpointResponse(
                200,
                Map.of("Authorization", "Bearer response-token"),
                """
                {
                  "probe":{"key":"mcp.jvm.diagnose#key","hitCount":0,"lastHitEpoch":0},
                  "runtime":{"mode":"observe","serverEpoch":123,"appPort":{"value":8080,"source":"agent","confidence":0.9}}
                }
                """,
                configuration);
    }

    private ProbeCheckAction action(ProbeEndpointConfiguration configuration, ProbeEndpointClient client) {
        return new ProbeCheckAction(
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
