package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.health;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Executes the bounded, wrapped-only readiness request required before a Performance workload. */
public final class PerformanceTargetHealthCheck {

    private final TransportExecutionFeature transport;

    /** Creates the target health-check collaborator over the public Transport Feature. */
    public PerformanceTargetHealthCheck(TransportExecutionFeature transport) {
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
    }

    /** Returns a stable readiness outcome without exposing target response bodies. */
    public Result verify(String url, int timeoutMs) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", "GET");
        request.put("url", url);
        request.put("timeoutMs", timeoutMs);
        var result = transport.execute(new ExecuteTransportRequest(TransportProtocol.HTTP, request, true));
        return "pass".equals(result.status())
                ? Result.success()
                : Result.failure("performance_target_healthcheck_failed");
    }

    /** Immutable bounded target readiness result. */
    public record Result(boolean ready, String reasonCode) {

        private static Result success() {
            return new Result(true, null);
        }

        private static Result failure(String reasonCode) {
            return new Result(false, reasonCode);
        }
    }
}
