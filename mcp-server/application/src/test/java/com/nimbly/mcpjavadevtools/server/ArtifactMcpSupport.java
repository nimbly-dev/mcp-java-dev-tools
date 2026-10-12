package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Artifact scenario inputs and semantic assertions over the existing packaged-process driver. */
abstract class ArtifactMcpSupport extends McpServerStdioSupport {
    private static final AtomicInteger IDS = new AtomicInteger(1000);
    static final String PROJECT = "artifact-it";
    static final String SECRET = "artifact-secret-sentinel";
    static final Map<String, List<String>> ACTIONS = Map.of(
            "probe_config", List.of("read", "validate", "upsert", "reload"),
            "project_context", List.of("read", "validate", "upsert", "list"),
            "regression_plan", List.of("read", "validate", "upsert", "list"),
            "performance_plan", List.of("read", "validate", "upsert", "list"),
            "security_plan", List.of("read", "validate", "upsert", "list"),
            "run_result", List.of("read", "upsert", "list", "rebuild", "backfill", "cutover", "query", "cleanup"),
            "execution_export", List.of("read", "list", "generate"));

    static McpServerProcess start(Path workspace) throws Exception {
        McpServerProcess server = McpServerProcess.start(jarPath(), workspace, "--workspace-root=" + workspace);
        initialize(server);
        return server;
    }

    static JsonNode execute(McpServerProcess server, String operation, Map<String, Object> input, boolean confirmed)
            throws Exception {
        int id = IDS.incrementAndGet();
        server.send(executeRequest(id, operation, input, confirmed));
        JsonNode response = toolPayload(server.responseFor(id, Duration.ofSeconds(45)));
        assertThat(response.toString()).as("MCP output must exclude secret values").doesNotContain(SECRET);
        JavaItScenario.observe(operation, response.path("result").path("status").asText(response.path("status").asText()),
                response.path("result").path("reasonCode").asText(response.path("reasonCode").asText()));
        return response;
    }

    static JsonNode artifact(McpServerProcess server, String action, Map<String, Object> input) throws Exception {
        JsonNode response = execute(server, "artifact_management." + action, input, true);
        assertThat(response.path("status").asText()).as(action).isEqualTo("succeeded");
        return response.path("result");
    }

    static JsonNode ok(McpServerProcess server, String action, Map<String, Object> input) throws Exception {
        JsonNode result = artifact(server, action, input);
        assertThat(result.path("status").asText()).as(action + ": " + result.path("reasonCode").asText()).isEqualTo("ok");
        assertThat(result.path("reasonCode").asText()).as(action).isEqualTo("success");
        return result.path("details");
    }

    static void blocked(McpServerProcess server, String action, Map<String, Object> input, String reason) throws Exception {
        JsonNode result = artifact(server, action, input);
        assertThat(result.path("resultType").asText()).as(action).isEqualTo("report");
        assertThat(result.path("status").asText()).as(action).isEqualTo(reason);
        assertThat(result.path("reasonCode").asText()).as(action).isEqualTo(reason);
    }

    static ObjectNode project(Path workspace) {
        ObjectNode result = JSON.createObjectNode();
        ObjectNode context = result.putArray("workspaces").addObject().put("projectRoot", workspace.toString());
        context.putObject("defaults").putObject("orchestrator")
                .put("resumePollMax", 1).put("resumePollIntervalMs", 10).put("resumePollTimeoutMs", 100);
        for (String suite : List.of("regression", "performance", "security")) {
            context.withArray("executionProfiles").addObject().put("executionProfile", suite)
                    .put("executionPolicy", "stop_on_fail").put("suiteType", suite)
                    .putArray("plans").addObject().put("order", 1).put("planName", "plan");
        }
        context.putObject("variables").putObject("contextBindings").put("auth.bearer", "AUTH_TOKEN");
        return result;
    }

    static void createProject(McpServerProcess server, Path workspace) throws Exception {
        JsonNode details = ok(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project(workspace)));
        assertThat(details.path("updateMode").asText()).isEqualTo("created");
        assertThat(details.path("stateStore").path("schemaVersion").asInt()).isEqualTo(3);
        assertThat(workspace.resolve(".mcpjvm/" + PROJECT + "/projects.json")).isRegularFile();
        assertThat(workspace.resolve(".mcpjvm/" + PROJECT + "/run-state.sqlite")).isRegularFile();
    }

    static Map<String, Object> planInput(String suite, Object payload) {
        return Map.of("projectName", PROJECT, "planName", "plan", "payload", payload);
    }

    static ObjectNode plan(String suite, String url) {
        ObjectNode payload = JSON.createObjectNode();
        payload.putObject("metadata").put("specVersion", "1.0.0").put("suiteType", suite)
                .putObject("execution").put("intent", suite);
        payload.put("plan", suite + " generated storage scenario\n");
        ObjectNode contract = payload.putObject("contract").put("suiteType", suite);
        if (suite.equals("performance")) {
            contract.putObject("loadModel").put("mode", "concurrency").put("concurrency", 2).put("durationSeconds", 1);
            contract.putObject("successCriteria").put("maxErrorRatePct", 0).put("minThroughputPerSec", 1).put("p95LatencyMs", 1000);
            contract.putObject("workloadProvider").put("type", "jmeter").put("mode", "generated_http");
            ObjectNode entry = contract.putArray("entrypoints").addObject();
            entry.putObject("transport").put("protocol", "http").put("baseUrl", url);
            entry.putObject("request").put("method", "GET").put("path", "/actuator/health");
        } else if (suite.equals("security")) {
            contract.put("securityMode", "blackbox");
            contract.putArray("entrypoints").addObject().put("id", "health")
                    .putObject("transport").put("type", "http").put("method", "GET").put("path", "/actuator/health");
        } else {
            contract.putArray("targets").addObject().put("id", "fixture");
            ObjectNode step = contract.putArray("steps").addObject().put("order", 1).put("id", "health").put("protocol", "http");
            step.putObject("transport").putObject("http").put("method", "GET").put("url", url);
            step.putArray("expect").addObject().put("id", "status").put("actualPath", "response.statusCode")
                    .put("operator", "field_equals").put("expected", 200);
        }
        return payload;
    }

    static Map<String, Object> runInput(String runId) {
        return Map.of("projectName", PROJECT, "planName", "plan", "runId", runId);
    }

    static JsonNode query(McpServerProcess server, String surface, Map<String, Object> query) throws Exception {
        return ok(server, "run_result.query", Map.of("projectName", PROJECT, "stateSurface", surface, "query", query)).path("query");
    }
}
