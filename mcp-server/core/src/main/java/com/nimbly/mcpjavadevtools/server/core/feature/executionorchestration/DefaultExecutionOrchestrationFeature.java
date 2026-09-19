package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import java.util.List;
import java.util.Map;

/** COMPATIBILITY_RETAINED_UNTIL_611: used by ExecutionOrchestrationMcpTool; delete with that #611 adapter. */
public final class DefaultExecutionOrchestrationFeature implements ExecutionOrchestrationFeature {

    private final List<? extends ExecutionOrchestrationActionHandler> handlers;

    public DefaultExecutionOrchestrationFeature(List<? extends ExecutionOrchestrationActionHandler> handlers) {
        this.handlers = List.copyOf(handlers);
        new EnumActionDispatcher<>(ExecutionOrchestrationAction.class, this.handlers);
    }

    public ExecutionOrchestrationActionHandler operationOwner(ExecutionOrchestrationAction action) {
        return handlers.stream().filter(handler -> handler.action() == action).findFirst().orElseThrow();
    }

    @Override
    public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
        if (request == null || request.action() == null) {
            return ExecutionOrchestrationResult.blocked(
                    "execution_orchestration_request_invalid", "an execute action is required", Map.of());
        }
        return operationOwner(request.action()).execute(request);
    }
}
