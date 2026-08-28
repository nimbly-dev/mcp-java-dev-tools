package com.nimbly.mcpjavadevtools.server.mcp.tools.executionorchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.ExecutionOrchestrationFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.model.result.ExecutionOrchestrationResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionOrchestrationMcpToolTest {

    @Test
    void mapsTheCanonicalActionAndInputContractToCoreAndFlattensTheResult() {
        AtomicReference<com.fasterxml.jackson.databind.JsonNode> captured = new AtomicReference<>();
        ExecutionOrchestrationFeature feature = request -> {
            captured.set(request.input());
            return new ExecutionOrchestrationResult("pass", "ok", null, Map.of(), Map.of(
                    "action", "execute", "suiteRunId", "run-1", "progressSummary", Map.of("status", "pass")));
        };
        var tool = new ExecutionOrchestrationMcpTool(feature, new ObjectMapper());

        var result = tool.invokeMcpRequest(new McpActionRequest<>("execute",
                new ExecutionOrchestrationMcpRequest("demo", "nightly", "run-1", 2)));

        assertThat(captured.get().toString()).contains("projectName").contains("executionProfile")
                .contains("maxPlansPerCall").doesNotContain("runtimeCredentialContext");
        assertThat(result.status()).isEqualTo("pass");
        assertThat(result.flattenedDetails()).containsEntry("suiteRunId", "run-1");
    }

    @Test
    void rejectsNonCanonicalActionBeforeInvokingCore() {
        ExecutionOrchestrationFeature feature = request -> { throw new AssertionError("Core must not be invoked"); };
        var tool = new ExecutionOrchestrationMcpTool(feature, new ObjectMapper());

        var result = tool.invokeMcpRequest(new McpActionRequest<>("resume",
                new ExecutionOrchestrationMcpRequest("demo", "nightly", null, null)));

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.reasonCode()).isEqualTo("execution_action_invalid");
    }
}
