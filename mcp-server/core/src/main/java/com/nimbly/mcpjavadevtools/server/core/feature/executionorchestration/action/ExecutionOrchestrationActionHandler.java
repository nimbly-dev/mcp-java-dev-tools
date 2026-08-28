package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;

/** Typed execution orchestration action-handler contract. */
public interface ExecutionOrchestrationActionHandler extends ActionHandler<ExecutionOrchestrationAction,
        ExecutionOrchestrationRequest, ExecutionOrchestrationResult> {
}
