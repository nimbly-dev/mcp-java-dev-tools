package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration;

import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;

/** Spring-independent public entry point for bounded execution-profile orchestration. */
public interface ExecutionOrchestrationFeature {

    /** Executes one orchestration action. */
    ExecutionOrchestrationResult execute(ExecutionOrchestrationRequest request);
}
