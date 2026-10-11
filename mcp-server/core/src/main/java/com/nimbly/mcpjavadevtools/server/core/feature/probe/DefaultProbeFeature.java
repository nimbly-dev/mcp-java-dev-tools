package com.nimbly.mcpjavadevtools.server.core.feature.probe;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Public implementation of the consolidated Probe Feature that executes only complete real actions.
 *
 * <p>The constructor rejects incomplete action sets. Application composition can
 * therefore expose the Feature only after every ticket-owned action exists.</p>
 */
public class DefaultProbeFeature implements ProbeFeature {

    private final Map<ProbeAction, ProbeActionHandler> handlers;

    /** Creates a catalog for the complete public Probe action allowlist. */
    public DefaultProbeFeature(List<? extends ProbeActionHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        Map<ProbeAction, ProbeActionHandler> indexed = new EnumMap<>(ProbeAction.class);
        for (ProbeActionHandler handler : handlers) {
            ProbeActionHandler checked = Objects.requireNonNull(handler, "handler must not be null");
            ProbeAction action = Objects.requireNonNull(checked.action(), "handler action must not be null");
            if (indexed.putIfAbsent(action, checked) != null) {
                throw new IllegalArgumentException("Duplicate Probe action owner: " + action.value());
            }
        }
        if (indexed.size() != ProbeAction.values().length) {
            throw new IllegalArgumentException("Probe handlers must cover every Probe action");
        }
        this.handlers = Map.copyOf(indexed);
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
            return handlers.get(request.action()).execute(request);
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() != ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            Thread.currentThread().interrupt();
            return ProbeResult.blocked(ProbeReasonCode.WAIT_INTERRUPTED, ProbeReasonMetadata.status());
        }
    }

}
