package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PerformanceTargetHealthCheckTest {

    @Test
    void executesTheHealthRequestThroughWrappedTransport() {
        AtomicReference<ExecuteTransportRequest> received = new AtomicReference<>();
        TransportExecutionFeature transport = request -> {
            received.set((ExecuteTransportRequest) request);
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "", 1);
        };

        var result = new PerformanceTargetHealthCheck(transport).verify("http://127.0.0.1:8080/health", 1_500);

        assertThat(result.ready()).isTrue();
        assertThat(received.get().wrappedOnly()).isTrue();
        assertThat(received.get().request()).containsEntry("method", "GET").containsEntry("timeoutMs", 1_500);
    }

    @Test
    void returnsAStableFailureForAnUnhealthyTarget() {
        TransportExecutionFeature transport = request -> ExecuteTransportResult.httpResponse("fail_http", "http", 503, Map.of(), "", 1);

        var result = new PerformanceTargetHealthCheck(transport).verify("http://127.0.0.1:8080/health", 1_500);

        assertThat(result.ready()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("performance_target_healthcheck_failed");
    }
}
