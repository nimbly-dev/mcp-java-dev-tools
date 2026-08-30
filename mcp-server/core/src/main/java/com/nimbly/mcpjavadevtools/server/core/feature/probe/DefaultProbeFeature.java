package com.nimbly.mcpjavadevtools.server.core.feature.probe;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointClientException;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.ProbeEndpointFailureKind;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonCode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeReasonMetadata;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.operation.ProbeOperationCatalog;
import java.util.List;
import java.util.Objects;

/**
 * Public implementation of the consolidated Probe Feature that executes only complete real actions.
 *
 * <p>The constructor rejects incomplete action sets. Application composition can
 * therefore expose the Feature only after every ticket-owned action exists.</p>
 */
public final class DefaultProbeFeature implements ProbeFeature {

    private final ProbeOperationCatalog operationCatalog;

    /** Creates a catalog for the complete public Probe action allowlist. */
    public DefaultProbeFeature(List<? extends ProbeActionHandler> handlers) {
        this(new ProbeOperationCatalog(handlers));
    }

    /** Creates the Feature from the complete capability-owned operation catalog. */
    public DefaultProbeFeature(ProbeOperationCatalog operationCatalog) {
        this.operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog must not be null");
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
            return operationCatalog.execute(request.action(), request);
        } catch (ProbeEndpointClientException exception) {
            if (exception.failureKind() != ProbeEndpointFailureKind.INTERRUPTED) {
                throw exception;
            }
            Thread.currentThread().interrupt();
            return ProbeResult.blocked(ProbeReasonCode.WAIT_INTERRUPTED, ProbeReasonMetadata.status());
        }
    }

}
