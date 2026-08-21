package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request.TransportExecutionRequest;

/** Spring-independent public Core boundary for transport execution. */
public interface TransportExecutionFeature {

    /**
     * Executes one internally action-discriminated transport request.
     *
     * @param request validated Feature request
     * @return deterministic transport outcome
     */
    ExecuteTransportResult execute(TransportExecutionRequest request);
}
