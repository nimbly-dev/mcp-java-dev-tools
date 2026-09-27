package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.policy.TransportExecutionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProviderRegistry;
import java.util.Map;
import java.util.Objects;

/** Executes bounded transport requests through the provider registry. */
public class ExecuteTransportAction implements TransportExecutionFeature {

    private final TransportExecutionPolicy policy;
    private final TransportProviderRegistry providers;

    /**
     * Creates the action with Core-owned policy and provider boundaries.
     *
     * @param policy active wrapper policy
     * @param providers complete protocol provider registry
     */
    public ExecuteTransportAction(
            TransportExecutionPolicy policy,
            TransportProviderRegistry providers) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.providers = Objects.requireNonNull(providers, "providers must not be null");
    }

    /** {@inheritDoc} */
    @Override
    public ExecuteTransportResult execute(ExecuteTransportRequest request) {
        if (request == null) {
            return ExecuteTransportResult.blockedInvalid(
                    "transport_request_invalid",
                    "Transport request is invalid.",
                    null,
                    1);
        }
        if (request.wrappedOnly() && policy.allowNonWrappedExecutable()) {
            return ExecuteTransportResult.blockedInvalid(
                    "wrapper_policy_violation",
                    "wrappedOnly=true requested but probe registry allows non-wrapped executable transport.",
                    request.protocol().value(),
                    1,
                    Map.of(
                            "failedStep", "transport_execute_policy",
                            "protocol", request.protocol().value()));
        }
        return providers.providerFor(request.protocol()).execute(request);
    }
}
