package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.impl.ProbeActuateAction;
import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateResult;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProbeActuateActionTest {

    @Test
    void serializesSessionValuesSafelyAndReturnsConfirmedArmState() {
        ProbeEndpointConfiguration configuration = configuration();
        AtomicReference<ProbeEndpointRequest> requestCapture = new AtomicReference<>();
        ProbeEndpointClient client = request -> {
            requestCapture.set(request);
            return new ProbeEndpointResponse(
                    200,
                    Map.of(),
                    "{\"ok\":true,\"action\":\"arm\",\"sessionId\":\"session-1\",\"mode\":\"actuate\",\"scopeState\":\"armed\"}",
                    configuration);
        };

        ProbeResult result = action(configuration, client).execute(new ProbeActuateRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                ProbeActuateCommand.ARM,
                "session-1",
                "audit\"id",
                "example.Work#doIt:17",
                true,
                1000L,
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(((ProbeActuateResult) result.actionResult().orElseThrow()).scopeState()).isEqualTo("armed");
        assertThat(requestCapture.get().payload()).contains("audit\\\"id");
    }

    @Test
    void rejectsArmFieldsOnDisarmWithoutCallingTheSidecar() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> {
            throw new AssertionError("unexpected endpoint invocation");
        };

        ProbeResult result = action(configuration, client).execute(new ProbeActuateRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                ProbeActuateCommand.DISARM,
                "session-1",
                null,
                "example.Work#doIt:17",
                null,
                null,
                null));

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.DISARM_FIELDS_NOT_ALLOWED);
    }

    private ProbeActuateAction action(ProbeEndpointConfiguration configuration, ProbeEndpointClient client) {
        return new ProbeActuateAction(
                new ProbeTargetResolver(configuration, null),
                configuration,
                client,
                new ProbeResponseCompactionPolicy(false, 64, 2, 8, 64, Set.of("content-type")));
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
