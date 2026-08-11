package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.impl.ProbeWaitForHitAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.waitforhit.ProbeWaitForHitResult;
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
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProbeWaitForHitActionTest {

    @Test
    void confirmsOnlyAPostBaselineLineHit() {
        ProbeEndpointConfiguration configuration = configuration();
        AtomicInteger calls = new AtomicInteger();
        ProbeEndpointClient client = request -> statusResponse(calls.incrementAndGet(), configuration);

        ProbeResult result = action(configuration, client, duration -> { }).execute(request());

        ProbeWaitForHitResult wait = (ProbeWaitForHitResult) result.actionResult().orElseThrow();
        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(wait.outcome().name()).isEqualTo("LINE_HIT");
        assertThat(wait.observedHitCount()).isEqualTo(1);
    }

    @Test
    void restoresInterruptStatusInsteadOfSwallowingCancellation() {
        ProbeEndpointConfiguration configuration = configuration();
        ProbeEndpointClient client = request -> new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"probe\":{\"key\":\"example.Probe#run:12\",\"hitCount\":0,\"lastHitEpoch\":0,\"lineResolvable\":true}}",
                configuration);

        ProbeResult result = action(configuration, client, duration -> {
            throw new InterruptedException("test interruption");
        }).execute(request());

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.WAIT_INTERRUPTED);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void retriesTypedUnreachableStatusCallsOnlyWhenThePolicyEnablesIt() {
        ProbeEndpointConfiguration configuration = configuration(true);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        ProbeEndpointClient client = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                throw new ProbeEndpointClientException(ProbeEndpointFailureKind.UNREACHABLE, "offline");
            }
            return statusResponse(call - 1, configuration);
        };

        ProbeResult result = action(configuration, client, duration -> sleeps.incrementAndGet()).execute(request());

        assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.SUCCESS);
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasValue(1);
    }

    private ProbeWaitForHitAction action(
            ProbeEndpointConfiguration configuration,
            ProbeEndpointClient client,
            ProbeWaitSleeper sleeper) {
        ProbeStatusAction statusAction = new ProbeStatusAction(
                new ProbeTargetResolver(configuration, null),
                configuration,
                client,
                new ProbeResponseCompactionPolicy(false, 64, 4, 8, 64, Set.of("content-type")));
        return new ProbeWaitForHitAction(statusAction, configuration.requestPolicy(), Clock.systemUTC(), sleeper);
    }

    private ProbeWaitForHitRequest request() {
        return new ProbeWaitForHitRequest(
                new ProbeTargetSelector(null, "http://127.0.0.1:9191"),
                new ProbeKeySelector("example.Probe#run:12", null),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                1);
    }

    private ProbeEndpointResponse statusResponse(int call, ProbeEndpointConfiguration configuration) {
        long hitCount = call == 1 ? 0 : 1;
        long lastHitEpoch = call == 1 ? 0 : Long.MAX_VALUE;
        return new ProbeEndpointResponse(
                200,
                Map.of(),
                "{\"probe\":{\"key\":\"example.Probe#run:12\",\"hitCount\":" + hitCount
                        + ",\"lastHitEpoch\":" + lastHitEpoch + ",\"lineResolvable\":true}}",
                configuration);
    }

    private ProbeEndpointConfiguration configuration() {
        return configuration(false);
    }

    private ProbeEndpointConfiguration configuration(boolean unreachableRetryEnabled) {
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
                new ProbeRequestPolicy(
                        Duration.ofSeconds(15),
                        Duration.ofMillis(500),
                        1,
                        unreachableRetryEnabled,
                        3,
                        bounds),
                new ProbeEndpointLimits(64, 128, 4096, 65536, 1048576));
    }
}
