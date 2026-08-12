package com.nimbly.mcpjavadevtools.server.mcp.tools.jvmlifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms.JvmListResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.candidate.JvmCandidate;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionRequest;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class JvmLifecycleMcpToolTest {

    @Test
    void mapsListJvmsThroughTheCoreFeatureAndPreservesCompatibilityFields() {
        JvmLifecycleFeature feature = request -> JvmLifecycleResult.discovery(
                "jvm_discovery_unverified",
                new JvmListResult(List.of(new JvmCandidate(
                        "1234", "orders.jar", "sanitized_attach_descriptor",
                        "spring_boot_candidate", List.of("executable_jar_name"), 1L))));

        McpActionResponse response = new JvmLifecycleMcpTool(feature).invokeMcpRequest(
                new McpActionRequest<>("list_jvms", new JvmLifecycleMcpActionInput(
                        null, null, null, null, null, null, null)));

        assertThat(response.resultType()).isEqualTo("jvm_list");
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.reasonCode()).isEqualTo("jvm_discovery_unverified");
        assertThat(response.details().get("jvms").toString())
                .contains("attachmentState", "probeState");
    }

    @Test
    void blocksInvalidMutationInputBeforeInvokingCore() {
        JvmLifecycleFeature feature = request -> {
            throw new AssertionError("invalid input must not reach Core");
        };

        McpActionResponse response = new JvmLifecycleMcpTool(feature).invokeMcpRequest(
                new McpActionRequest<>("attach", new JvmLifecycleMcpActionInput(
                        "1234", 1L, false, null, null, null, null)));

        assertThat(response.resultType()).isEqualTo("report");
        assertThat(response.status()).isEqualTo("blocked");
        assertThat(response.reasonCode()).isEqualTo("jvm_lifecycle_request_invalid");
    }

    @Test
    void mapsUnexpectedFeatureFailureAsAnInternalBoundaryError() {
        JvmLifecycleFeature feature = request -> {
            throw new IllegalStateException("programming failure");
        };

        McpActionResponse response = new JvmLifecycleMcpTool(feature).invokeMcpRequest(
                new McpActionRequest<>("attach", new JvmLifecycleMcpActionInput(
                        "1234", 1L, true, null, null, null, null)));

        assertThat(response.resultType()).isEqualTo("report");
        assertThat(response.status()).isEqualTo("internal_error");
        assertThat(response.reasonCode()).isEqualTo("internal_error");
    }
}
