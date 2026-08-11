package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.ProbeActuateOperation;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.ProbeActuateRequestValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate.ProbeActuateResult;
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
 * Coordinates session-scoped arm and disarm requests against the existing Sidecar.
 */
@RequiredArgsConstructor
public final class ProbeActuateAction implements ProbeActionHandler {

    @NonNull private final ProbeTargetResolver targetResolver;
    @NonNull private final ProbeEndpointConfiguration endpointConfiguration;
    @NonNull private final ProbeEndpointClient endpointClient;
    @NonNull private final ProbeResponseCompactionPolicy compactionPolicy;

    /**
     * Dispatches only typed actuation requests.
     *
     * @param request typed consolidated Probe request
     * @return deterministic actuation outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (!(request instanceof ProbeActuateRequest actuateRequest)) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        ProbeResult validation = ProbeActuateRequestValidator.validate(actuateRequest, compactionPolicy);
        if (validation != null) {
            return validation;
        }
        try {
            return new ProbeActuateOperation(targetResolver, endpointConfiguration, endpointClient, compactionPolicy)
                    .actuate(actuateRequest);
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() == ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            return ProbeResult.withActionResult(
                    ProbeReasonCode.PROBE_UNREACHABLE,
                    ProbeReasonMetadata.status(),
                    new ProbeActuateResult(false, null, null, null, null, null, null, null, null, null, "probe_unreachable"));
        }
    }

    /**
     * Returns the public action implemented here.
     *
     * @return actuate action discriminator
     */
    @Override
    public ProbeAction action() {
        return ProbeAction.ACTUATE;
    }
}
