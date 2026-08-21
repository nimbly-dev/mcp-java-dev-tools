package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request.TransportExecutionRequest;

/** Typed action-handler contract for Transport Execution. */
public interface TransportExecutionActionHandler extends ActionHandler<
        TransportExecutionAction, TransportExecutionRequest, ExecuteTransportResult> {
}
