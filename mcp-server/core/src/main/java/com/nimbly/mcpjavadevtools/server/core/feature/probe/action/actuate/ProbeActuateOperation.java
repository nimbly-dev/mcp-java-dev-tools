package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Invokes one validated session-scoped Sidecar actuation operation.
 */
@RequiredArgsConstructor
public final class ProbeActuateOperation {

    @NonNull private final ProbeTargetResolver targetResolver;
    @NonNull private final ProbeEndpointConfiguration endpointConfiguration;
    @NonNull private final ProbeEndpointClient endpointClient;
    @NonNull private final ProbeResponseCompactionPolicy compactionPolicy;

    public ProbeResult actuate(ProbeActuateRequest request) {
        ProbeTargetResolution resolution = targetResolver.resolve(request.targetSelector());
        if (resolution instanceof UnresolvedProbeTarget unresolved) {
            return unresolved.result();
        }
        ProbeEndpointResponse response = endpointClient.exchange(ProbeActuateEndpointRequestFactory.create(
                ((ResolvedProbeTarget) resolution).target(), endpointConfiguration, request));
        return ProbeActuateResponseMapper.toResult(request, response, compactionPolicy);
    }
}
