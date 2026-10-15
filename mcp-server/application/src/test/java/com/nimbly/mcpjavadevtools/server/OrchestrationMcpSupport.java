package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;

/** Capability assertions over the existing packaged CDE and generated Artifact support. */
abstract class OrchestrationMcpSupport extends RegressionMcpSupport {
    static final String OPERATION = "execution_orchestration.execute";

    static ObjectNode profile(ObjectNode project) {
        return (ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0);
    }

    static ObjectNode root(ObjectNode project) {
        return (ObjectNode) project.path("workspaces").get(0);
    }

    static JsonNode orchestrate(McpServerProcess server, String profile, String run, int slice) throws Exception {
        Map<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("projectName", PROJECT);
        input.put("executionProfile", profile);
        if (run != null) {
            input.put("suiteRunId", run);
        }
        if (slice > 0) {
            input.put("maxPlansPerCall", slice);
        }
        JsonNode response = execute(server, OPERATION, input, true);
        assertThat(response.path("status").asText()).as(response.toPrettyString()).isEqualTo("succeeded");
        return response.path("result");
    }

    static JsonNode checkpoint(Path workspace, JsonNode result) throws Exception {
        Path file = workspace.resolve(result.path("details").path("statusArtifactPath").asText()).normalize();
        assertThat(file.startsWith(workspace)).isTrue();
        assertThat(file).isRegularFile();
        JsonNode persisted = JSON.readTree(file.toFile());
        assertThat(persisted.path("status")).isEqualTo(result.path("status"));
        assertThat(persisted.path("reasonCode")).isEqualTo(result.path("reasonCode"));
        assertThat(persisted.path("planRuns")).isEqualTo(result.path("details").path("planRuns"));
        assertThat(persisted.path("profileFingerprint").asText()).matches("[a-f0-9]{64}");
        return persisted;
    }

    static JsonNode planRun(McpServerProcess server, String suite, String name, String run) throws Exception {
        JsonNode read = ok(server, "run_result.read", Map.of("projectName", PROJECT, "suiteType", suite,
                "planName", name, "runId", run));
        assertThat(read.path("artifact").path("status").asText()).isEqualTo("pass");
        return read.path("artifact");
    }

    static void assertStatus(JsonNode result, String status, String reason) {
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo(status);
        assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
    }
}
