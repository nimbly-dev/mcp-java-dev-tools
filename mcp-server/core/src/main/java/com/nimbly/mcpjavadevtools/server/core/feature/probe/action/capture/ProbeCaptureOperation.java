package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointResponse;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetResolution;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ResolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.UnresolvedProbeTarget;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;

/**
 * Performs one resolved capture lookup through the existing Sidecar endpoint.
 */
public final class ProbeCaptureOperation {

    private ProbeCaptureOperation() {
    }

    public static ProbeResult retrieve(
            ProbeTargetResolver targetResolver,
            ProbeEndpointConfiguration endpointConfiguration,
            ProbeEndpointClient endpointClient,
            ProbeResponseCompactionPolicy compactionPolicy,
            ProbeCaptureRequest request) {
        ProbeTargetResolution resolution = targetResolver.resolve(request.targetSelector());
        if (resolution instanceof UnresolvedProbeTarget unresolved) {
            return unresolved.result();
        }
        ProbeEndpointResponse response = endpointClient.exchange(ProbeCaptureEndpointRequestFactory.create(
                ((ResolvedProbeTarget) resolution).target(),
                endpointConfiguration,
                request));
        return ProbeCaptureResponseMapper.toResult(response, request.captureId(), compactionPolicy);
    }
}
