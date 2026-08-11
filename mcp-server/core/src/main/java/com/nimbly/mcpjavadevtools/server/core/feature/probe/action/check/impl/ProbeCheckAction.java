package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.ProbeCheckEndpointCaller;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.ProbeCheckRecommendations;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check.ProbeCheckEndpointResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Executes the bounded reset-plus-status Probe availability diagnostic.
 */
@RequiredArgsConstructor
public final class ProbeCheckAction implements ProbeActionHandler {

    @NonNull private final ProbeTargetResolver targetResolver;
    @NonNull private final ProbeEndpointConfiguration endpointConfiguration;
    @NonNull private final ProbeEndpointClient endpointClient;
    @NonNull private final ProbeResponseCompactionPolicy compactionPolicy;

    /**
     * Executes one reset-plus-status diagnostic without exposing endpoint secrets.
     *
     * @param request typed check request
     * @return deterministic check outcome
     */
    public ProbeResult execute(ProbeCheckRequest request) {
        if (request == null) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        try {
            return executeResolved(request);
        } catch (IllegalArgumentException exception) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
    }

    /**
     * Dispatches only the check request type owned by this action.
     *
     * @param request typed consolidated Probe request
     * @return deterministic check outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (!(request instanceof ProbeCheckRequest checkRequest)) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        return execute(checkRequest);
    }

    /**
     * Returns the public Probe action implemented here.
     *
     * @return check action discriminator
     */
    @Override
    public ProbeAction action() {
        return ProbeAction.CHECK;
    }

    private ProbeResult executeResolved(ProbeCheckRequest request) {
        ProbeTargetResolution resolution = targetResolver.resolve(request.targetSelector());
        if (resolution instanceof UnresolvedProbeTarget unresolved) {
            return unresolved.result();
        }
        ProbeTarget target = ((ResolvedProbeTarget) resolution).target();
        ProbeCheckEndpointResult reset = ProbeCheckEndpointCaller.call(
                target,
                request,
                endpointConfiguration,
                endpointClient,
                compactionPolicy,
                true);
        ProbeCheckEndpointResult status = ProbeCheckEndpointCaller.call(
                target,
                request,
                endpointConfiguration,
                endpointClient,
                compactionPolicy,
                false);
        ProbeCheckResult result = new ProbeCheckResult(
                reset,
                status,
                status.runtime(),
                ProbeCheckRecommendations.forEndpoints(reset, status));
        return result.healthy()
                ? ProbeResult.success(result)
                : ProbeResult.withActionResult(
                        ProbeReasonCode.DIAGNOSE_FAILED,
                        ProbeReasonMetadata.diagnostics(),
                        result);
    }

}
