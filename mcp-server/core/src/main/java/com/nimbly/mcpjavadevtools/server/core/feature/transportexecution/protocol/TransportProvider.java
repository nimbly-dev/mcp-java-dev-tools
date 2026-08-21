package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;

/** Purpose-owned provider contract for one recognized transport protocol. */
public interface TransportProvider {

    /** @return protocol owned by this provider */
    TransportProtocol protocol();

    /**
     * Executes or deterministically rejects one request.
     *
     * @param request validated transport request
     * @return normalized provider outcome
     */
    ExecuteTransportResult execute(ExecuteTransportRequest request);
}
