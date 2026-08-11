package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture.ProbeCaptureOperation;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture.ProbeCaptureResult;
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
 * Retrieves one existing Sidecar capture without exposing captured values.
 */
@RequiredArgsConstructor
public final class ProbeCaptureAction implements ProbeActionHandler {

    @NonNull private final ProbeTargetResolver targetResolver;
    @NonNull private final ProbeEndpointConfiguration endpointConfiguration;
    @NonNull private final ProbeEndpointClient endpointClient;
    @NonNull private final ProbeResponseCompactionPolicy compactionPolicy;

    /**
     * Dispatches only typed capture requests.
     *
     * @param request typed consolidated Probe request
     * @return deterministic capture outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (!(request instanceof ProbeCaptureRequest captureRequest)
                || captureRequest.captureId() == null
                || captureRequest.captureId().isBlank()) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        try {
            return ProbeCaptureOperation.retrieve(
                    targetResolver,
                    endpointConfiguration,
                    endpointClient,
                    compactionPolicy,
                    captureRequest);
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() == ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            return ProbeResult.withActionResult(
                    ProbeReasonCode.PROBE_UNREACHABLE,
                    ProbeReasonMetadata.status(),
                    new ProbeCaptureResult(false, null, "probe_unreachable"));
        }
    }

    /**
     * Returns the public action implemented here.
     *
     * @return capture action discriminator
     */
    @Override
    public ProbeAction action() {
        return ProbeAction.CAPTURE;
    }
}
