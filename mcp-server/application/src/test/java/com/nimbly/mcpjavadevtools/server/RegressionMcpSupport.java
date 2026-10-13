package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Generated Regression inputs and assertions using the shared packaged-JAR driver. */
abstract class RegressionMcpSupport extends ArtifactMcpSupport {
    static final String PROFILE = "regression";

    static ObjectNode context(Path workspace, String url, String... plans) {
        ObjectNode project = JSON.createObjectNode();
        ObjectNode root = project.putArray("workspaces").addObject().put("projectRoot", workspace.toString());
        root.putObject("defaults").putObject("orchestrator")
                .put("resumePollMax", 1).put("resumePollIntervalMs", 10).put("resumePollTimeoutMs", 100);
        ObjectNode profile = root.putArray("executionProfiles").addObject()
                .put("executionProfile", PROFILE).put("executionPolicy", "stop_on_fail").put("suiteType", "regression");
        profile.putObject("providedContext").put("apiBaseUrl", url).put("tenant", "test-tenant");
        for (int i = 0; i < plans.length; i++) {
            profile.withArray("plans").addObject().put("order", i + 1).put("planName", plans[i]).put("onFail", "inherit");
        }
        return project;
    }

    static void saveContext(McpServerProcess server, ObjectNode project) throws Exception {
        ok(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project));
    }

    static ObjectNode generatedPlan(String url) {
        ObjectNode plan = plan("regression", url);
        ((ObjectNode) plan.path("metadata").path("execution"))
                .put("probeVerification", false).put("pinStrictProbeKey", false);
        return plan;
    }

    static ObjectNode contract(ObjectNode plan) {
        return (ObjectNode) plan.path("contract");
    }

    static ObjectNode step(ObjectNode plan, int index) {
        return (ObjectNode) plan.path("contract").path("steps").get(index);
    }

    static ObjectNode http(ObjectNode step) {
        return (ObjectNode) step.path("transport").path("http");
    }

    static void expect(ObjectNode step, String path, String operator, Object expected) {
        ObjectNode row = step.withArray("expect").addObject().put("id", "assert-" + step.path("expect").size())
                .put("actualPath", path).put("operator", operator);
        row.set("expected", JSON.valueToTree(expected));
    }

    static void save(McpServerProcess server, String name, ObjectNode plan) throws Exception {
        ok(server, "regression_plan.upsert", Map.of("projectName", PROJECT, "planName", name, "payload", plan));
        JsonNode read = ok(server, "regression_plan.read", Map.of("projectName", PROJECT, "planName", name,
                "query", Map.of("select", List.of("metadata", "contract"))));
        assertThat(read.path("artifact").path("metadata")).isEqualTo(plan.path("metadata"));
        for (int index = 0; index < plan.path("contract").path("steps").size(); index++) {
            assertThat(read.path("artifact").path("contract").path("steps").get(index).path("extract"))
                    .as("Artifact reads preserve extraction classification and scope")
                    .isEqualTo(plan.path("contract").path("steps").get(index).path("extract"));
        }
    }

    static Map<String, Object> selector(String name, String run) {
        return Map.of("projectName", PROJECT, "executionProfile", PROFILE, "planName", name, "suiteRunId", run);
    }

    static JsonNode run(McpServerProcess server, String name, String run) throws Exception {
        JsonNode result = execute(server, "regression_suite.execute_plan", selector(name, run), true);
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("succeeded");
        return result.path("result");
    }

    static JsonNode assertRun(McpServerProcess server, Path workspace, String plan, String run, String status) throws Exception {
        Path path = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/" + plan + "/runs/" + run + "-1/execution.result.json");
        assertThat(path).isRegularFile();
        JsonNode persisted = JSON.readTree(path.toFile());
        assertThat(persisted.path("status").asText()).as(persisted.toPrettyString()).isEqualTo(status);
        JsonNode read = ok(server, "run_result.read", Map.of("projectName", PROJECT, "planName", plan, "runId", run + "-1"));
        assertThat(read.path("artifact")).isEqualTo(persisted);
        ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
        JsonNode rows = query(server, "run_state", Map.of("runId", run + "-1")).path("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("status").asText()).isEqualTo(status);
        return persisted;
    }
}
