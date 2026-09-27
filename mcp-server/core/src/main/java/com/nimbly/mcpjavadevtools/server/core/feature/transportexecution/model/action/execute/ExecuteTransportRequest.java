package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Validated Core request for transport execution. */
public record ExecuteTransportRequest(
        TransportProtocol protocol,
        Map<String, Object> request,
        boolean wrappedOnly) {

    /** Defensive-copy the protocol-specific payload at the Core boundary. */
    public ExecuteTransportRequest {
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(request, "request must not be null");
        request = Collections.unmodifiableMap(new LinkedHashMap<>(request));
    }

}
