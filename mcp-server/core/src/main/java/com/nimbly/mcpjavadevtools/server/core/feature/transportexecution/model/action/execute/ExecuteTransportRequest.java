package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request.TransportExecutionRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Validated Core request for the internally dispatched execute action. */
public record ExecuteTransportRequest(
        TransportProtocol protocol,
        Map<String, Object> request,
        boolean wrappedOnly) implements TransportExecutionRequest {

    /** Defensive-copy the protocol-specific payload at the Core boundary. */
    public ExecuteTransportRequest {
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(request, "request must not be null");
        request = Map.copyOf(new LinkedHashMap<>(request));
    }

    /** {@inheritDoc} */
    @Override
    public TransportExecutionAction action() {
        return TransportExecutionAction.EXECUTE;
    }
}
