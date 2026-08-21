package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.TransportExecutionActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.impl.ExecuteTransportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy.TransportExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProviderRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransportExecutionFeatureTest {

    @Test
    void dispatchesTheInternalExecuteActionWithoutAddingPublicActionInput() {
        TransportExecutionFeature feature = feature(() -> false);

        ExecuteTransportResult result = feature.execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP,
                Map.of("method", "GET", "url", "http://127.0.0.1"),
                true));

        assertThat(result.status()).isEqualTo("pass");
        assertThat(result.protocol()).isEqualTo("http");
    }

    @Test
    void blocksWrappedOnlyWhenTheActiveProbeRegistryAllowsBypass() {
        TransportExecutionFeature feature = feature(() -> true);

        ExecuteTransportResult result = feature.execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP, Map.of("method", "GET", "url", "http://127.0.0.1"), true));

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("wrapper_policy_violation");
        assertThat(result.reasonMeta()).containsEntry("failedStep", "transport_execute_policy")
                .containsEntry("protocol", "http");
    }

    @Test
    void unsupportedProvidersRemainDeterministic() {
        TransportExecutionFeature feature = feature(() -> false);

        ExecuteTransportResult result = feature.execute(new ExecuteTransportRequest(
                TransportProtocol.GRPC, Map.of("target", "service"), true));

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("transport_not_supported");
        assertThat(result.reasonMeta()).containsEntry("failedStep", "transport_execute_protocol")
                .containsEntry("protocol", "grpc");
    }

    private TransportExecutionFeature feature(TransportExecutionPolicy policy) {
        TransportProviderRegistry providers = new TransportProviderRegistry(List.of(
                provider(TransportProtocol.HTTP),
                provider(TransportProtocol.GRPC),
                provider(TransportProtocol.KAFKA),
                provider(TransportProtocol.CUSTOM)));
        TransportExecutionActionHandler action = new ExecuteTransportAction(policy, providers);
        return new DefaultTransportExecutionFeature(List.of(action));
    }

    private TransportProvider provider(TransportProtocol protocol) {
        return new TransportProvider() {
            @Override
            public TransportProtocol protocol() {
                return protocol;
            }

            @Override
            public ExecuteTransportResult execute(ExecuteTransportRequest request) {
                return protocol == TransportProtocol.HTTP
                        ? ExecuteTransportResult.httpResponse("pass", protocol.value(), 200, Map.of(), null, 1)
                        : ExecuteTransportResult.unsupported(protocol.value());
            }
        };
    }
}
