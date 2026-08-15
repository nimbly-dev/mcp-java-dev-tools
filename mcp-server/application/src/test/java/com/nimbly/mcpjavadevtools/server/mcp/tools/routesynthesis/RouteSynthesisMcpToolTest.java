package com.nimbly.mcpjavadevtools.server.mcp.tools.routesynthesis;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.RouteSynthesisFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.infertarget.InferTargetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.request.RouteSynthesisRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.result.RouteSynthesisResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteSynthesisMcpToolTest {

    @Test
    void mapsTheConsolidatedRequestThroughTheCoreFeatureBoundary() {
        RouteSynthesisFeature feature = request -> {
            assertThat(request).isInstanceOf(InferTargetRequest.class);
            InferTargetRequest infer = (InferTargetRequest) request;
            assertThat(infer.projectRootAbs()).isEqualTo("C:/workspace");
            assertThat(infer.additionalSourceRoots()).containsExactly("C:/workspace/shared");
            assertThat(infer.classHint()).isEqualTo("example.Controller");
            return RouteSynthesisResult.report(
                    "report", "target_type_not_found", "target_selection",
                    "refine_class_hint", "Refine classHint and rerun.");
        };

        McpActionResponse response = new RouteSynthesisMcpTool(feature).execute(
                RouteSynthesisMcpAction.infer_target, input());

        assertThat(response.resultType()).isEqualTo("report");
        assertThat(response.status()).isEqualTo("report");
        assertThat(response.reasonCode()).isEqualTo("target_type_not_found");
        assertThat(response.nextActionCode()).isEqualTo("refine_class_hint");
    }

    @Test
    void rejectsUnsupportedActionBeforeCoreInvocation() {
        RouteSynthesisFeature feature = request -> {
            throw new AssertionError("unsupported action must not reach Core");
        };

        String response = new RouteSynthesisMcpTool(feature).call(
                "{\"action\":\"unsupported\",\"input\":{\"projectRootAbs\":\"C:/workspace\"}}");

        assertThat(response).contains("\"status\":\"blocked_invalid\"");
        assertThat(response).contains("\"reasonCode\":\"invalid_request\"");
    }

    private RouteSynthesisMcpActionInput input() {
        return new RouteSynthesisMcpActionInput(
                "C:/workspace", List.of("C:/workspace/shared"), "example.Controller", "run", 42, 10,
                null, null, null, null, null, "line_probe", null, null, null,
                null, null, null, null);
    }
}
