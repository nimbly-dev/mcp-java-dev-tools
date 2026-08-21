package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;

/** Action-discriminated Core request contract. */
public interface TransportExecutionRequest {

    /** @return internal action selected by the Core Feature */
    TransportExecutionAction action();
}
