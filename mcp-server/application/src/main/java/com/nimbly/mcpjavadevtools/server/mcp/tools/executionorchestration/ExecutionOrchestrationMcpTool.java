package com.nimbly.mcpjavadevtools.server.mcp.tools.executionorchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.ExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.action.ExecutionOrchestrationAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.request.ExecutionOrchestrationRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Thin Spring AI adapter exposing execution_orchestration's execute action. */
@Component
public final class ExecutionOrchestrationMcpTool {

    private final ExecutionOrchestrationFeature feature;
    private final ObjectMapper mapper;

    /** Creates the adapter from the public Core Feature boundary. */
    public ExecutionOrchestrationMcpTool(ExecutionOrchestrationFeature feature, ObjectMapper mapper) {
        this.feature = feature;
        this.mapper = mapper;
    }

    /** Executes one bounded persisted execution profile. */
    @McpTool(
            name = "execution_orchestration",
            description = "Execute a persisted project execution profile through bounded suite routes.",
            generateOutputSchema = true)
    public McpActionResponse execute(
            @McpToolParam(description = "Required execution-orchestration action.") String action,
            @McpToolParam(description = "Action-specific persisted execution request.") ExecutionOrchestrationMcpRequest input) {
        return invokeMcpRequest(new McpActionRequest<>(action, input));
    }

    McpActionResponse invokeMcpRequest(McpActionRequest<ExecutionOrchestrationMcpRequest> request) {
        if (request == null || !"execute".equals(request.action()) || request.input() == null) {
            return new McpActionResponse("execution_orchestration", "blocked", "execution_action_invalid", null,
                    "provide action=execute and an input object", "execution_action_invalid", java.util.Map.of(), null,
                    java.util.Map.of("action", "execute"));
        }
        ExecutionOrchestrationMcpRequest inputRequest = request.input();
        ObjectNode input = mapper.createObjectNode();
        if (inputRequest.projectName() != null) {
            input.put("projectName", inputRequest.projectName());
        }
        if (inputRequest.executionProfile() != null) {
            input.put("executionProfile", inputRequest.executionProfile());
        }
        if (inputRequest.suiteRunId() != null) {
            input.put("suiteRunId", inputRequest.suiteRunId());
        }
        if (inputRequest.maxPlansPerCall() != null) {
            input.put("maxPlansPerCall", inputRequest.maxPlansPerCall());
        }
        return response(feature.execute(new ExecutionOrchestrationRequest(ExecutionOrchestrationAction.EXECUTE, input)));
    }

    private static McpActionResponse response(ExecutionOrchestrationResult result) {
        return new McpActionResponse(
                "execution_orchestration",
                result.status(),
                result.reasonCode(),
                null,
                result.nextAction(),
                result.reasonCode(),
                result.reasonMeta(),
                null,
                result.details());
    }
}
