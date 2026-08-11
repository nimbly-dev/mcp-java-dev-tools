package com.nimbly.mcpjavadevtools.server.core.feature.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.util.Arrays;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class DefaultProbeFeatureTest {

    @Test
    void normalizesInterruptionInCore() {
        DefaultProbeFeature feature = new DefaultProbeFeature(Arrays.stream(ProbeAction.values())
                .map(action -> handler(action, action == ProbeAction.CAPTURE
                        ? request -> {
                            throw new ProbeEndpointClientException(
                                    ProbeEndpointFailureKind.INTERRUPTED,
                                    "interrupted");
                        }
                        : request -> ProbeResult.success()))
                .toList());

        ProbeResult result;
        try {
            result = feature.execute(new CaptureRequest());
            assertThat(result.reasonCode()).isEqualTo(ProbeReasonCode.WAIT_INTERRUPTED);
            assertThat(result.status().name()).isEqualTo("BLOCKED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static ProbeActionHandler handler(
            ProbeAction action,
            Function<ProbeRequest, ProbeResult> execution) {
        return new ProbeActionHandler() {
            @Override
            public ProbeAction action() {
                return action;
            }

            @Override
            public ProbeResult execute(ProbeRequest request) {
                return execution.apply(request);
            }
        };
    }

    private static final class CaptureRequest implements ProbeRequest {

        @Override
        public ProbeAction action() {
            return ProbeAction.CAPTURE;
        }

        @Override
        public ProbeTargetSelector targetSelector() {
            return new ProbeTargetSelector(null, null);
        }
    }
}
