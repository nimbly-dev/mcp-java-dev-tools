package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.ProbeProfilerFailure;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.ProbeProfilerOperation;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.ProbeProfilerOutputStore;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.ProbeProfilerRequestValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Coordinates existing Sidecar profiler lifecycle actions and bounded downloads.
 */
@RequiredArgsConstructor
public final class ProbeProfilerAction implements ProbeActionHandler {

    @NonNull private final ProbeTargetResolver targetResolver;
    @NonNull private final ProbeEndpointConfiguration endpointConfiguration;
    @NonNull private final ProbeEndpointClient endpointClient;
    @NonNull private final ProbeResponseCompactionPolicy compactionPolicy;
    @NonNull private final ProbeProfilerOutputStore outputStore;

    /**
     * Dispatches only typed profiler requests.
     *
     * @param request typed consolidated Probe request
     * @return deterministic profiler outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (!(request instanceof ProbeProfilerRequest profilerRequest)) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        if (!ProbeProfilerRequestValidator.isValid(profilerRequest)) {
            return ProbeProfilerFailure.result(profilerRequest, "invalid_profiler_request", compactionPolicy);
        }
        try {
            return new ProbeProfilerOperation(
                    targetResolver,
                    endpointConfiguration,
                    endpointClient,
                    compactionPolicy,
                    outputStore).invoke(profilerRequest);
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() == ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            return ProbeResult.withActionResult(
                    ProbeReasonCode.PROBE_UNREACHABLE,
                    ProbeReasonMetadata.status(),
                    new ProbeProfilerResult(
                            null, "unreachable", false, null, null, null, null, null, null, null, null, null, null));
        }
    }

    /**
     * Returns the public action implemented here.
     *
     * @return profiler action discriminator
     */
    @Override
    public ProbeAction action() {
        return ProbeAction.PROFILER;
    }
}
