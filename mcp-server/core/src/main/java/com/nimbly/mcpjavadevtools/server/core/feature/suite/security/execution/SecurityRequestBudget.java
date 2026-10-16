package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.time.Duration;
import java.util.Map;

/** Enforces per-invocation rate, duration and request ceilings, including interruption. */
class SecurityRequestBudget {

    // The operation's five-minute bound and Transport's twenty-second request ceiling
    // remain enforced even when a persisted plan requests larger values.
    private static final int MAXIMUM_EXECUTION_MILLIS = 300_000;
    private static final int MAXIMUM_REQUEST_MILLIS = 20_000;
    private final long deadline;
    private final long intervalNanos;
    private final String reason;
    private long nextRequest;

    SecurityRequestBudget(JsonNode contract) {
        JsonNode policy = contract.path("safetyPolicy");
        deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Math.min(MAXIMUM_EXECUTION_MILLIS, policy.path("maxDurationMs").asLong(MAXIMUM_EXECUTION_MILLIS)));
        intervalNanos = policy.path("maxRequestsPerSecond").isIntegralNumber()
                ? java.util.concurrent.TimeUnit.SECONDS.toNanos(1) / policy.path("maxRequestsPerSecond").asLong() : 0;
        reason = "blackbox".equals(contract.path("securityMode").asText())
                ? "security_blackbox_duration_exceeded" : "security_sidecar_duration_exceeded";
    }

    ExecuteTransportResult execute(TransportExecutionFeature transport, Map<String, Object> payload) {
        try {
            long delay = Math.min(nextRequest, deadline) - System.nanoTime();
            if (delay > 0) {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);
            }
            long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining < 1) {
                return expired();
            }
            payload.put("timeoutMs", Math.min(MAXIMUM_REQUEST_MILLIS, remaining));
            nextRequest = System.nanoTime() + intervalNanos;
            ExecuteTransportResult result = transport.execute(new ExecuteTransportRequest(TransportProtocol.HTTP, payload, true));
            return System.nanoTime() >= deadline || (remaining < MAXIMUM_REQUEST_MILLIS
                    && "transport_request_timeout".equals(result.reasonCode())) ? expired() : result;
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return ExecuteTransportResult.blockedRuntime("security_execution_interrupted", "Security request interrupted", "http", 1);
        }
    }

    private ExecuteTransportResult expired() {
        return ExecuteTransportResult.blockedRuntime(reason, "Security request budget exhausted", "http", 1);
    }

    boolean expiredNow() {
        return System.nanoTime() >= deadline;
    }

    String reasonOr(String fallback) {
        return expiredNow() ? reason : fallback;
    }

    Duration probeTimeout() {
        long remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        return Duration.ofMillis(Math.max(1, Math.min(10_000, remaining)));
    }

    long deadline() {
        return deadline;
    }
}
