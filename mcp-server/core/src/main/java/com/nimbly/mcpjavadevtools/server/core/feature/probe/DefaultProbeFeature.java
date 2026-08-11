package com.nimbly.mcpjavadevtools.server.core.feature.probe;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import java.util.List;

/**
 * Public implementation of the consolidated Probe Feature that dispatches only complete real actions.
 *
 * <p>The constructor rejects incomplete action sets. Application composition can
 * therefore expose the Feature only after every ticket-owned action exists.</p>
 */
public final class DefaultProbeFeature implements ProbeFeature {

    private final EnumActionDispatcher<ProbeAction, ProbeRequest, ProbeResult> dispatcher;

    /**
     * Creates a dispatcher for the complete public Probe action allowlist.
     *
     * @param handlers action-owned real Core behavior
     */
    public DefaultProbeFeature(List<? extends ProbeActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(ProbeAction.class, handlers);
    }

    /**
     * Executes exactly one owned typed action request.
     *
     * @param request typed consolidated Probe request
     * @return deterministic action outcome
     */
    @Override
    public ProbeResult execute(ProbeRequest request) {
        if (request == null || request.action() == null) {
            return ProbeResult.failure(ProbeReasonCode.INVALID_REQUEST, ProbeReasonMetadata.inputValidation());
        }
        try {
            return dispatcher.dispatch(request.action(), request);
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() != ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            Thread.currentThread().interrupt();
            return ProbeResult.blocked(ProbeReasonCode.WAIT_INTERRUPTED, ProbeReasonMetadata.status());
        }
    }

}
