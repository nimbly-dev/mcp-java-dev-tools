package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.action.ExecutionOrchestrationActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import java.util.List;
import java.util.Map;

/** Default production execution-orchestration Core Feature. */
public final class DefaultExecutionOrchestrationFeature implements ExecutionOrchestrationFeature {

    private final EnumActionDispatcher<ExecutionOrchestrationAction, ExecutionOrchestrationRequest,
            ExecutionOrchestrationResult> dispatcher;

    /** Creates the feature from complete action handlers. */
    public DefaultExecutionOrchestrationFeature(List<? extends ExecutionOrchestrationActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(ExecutionOrchestrationAction.class, handlers);
    }

    @Override
    public ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request) {
        if (request == null || request.action() == null) {
            return ExecutionOrchestrationResult.blocked(
                    "execution_orchestration_request_invalid", "an execute action is required", Map.of());
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
