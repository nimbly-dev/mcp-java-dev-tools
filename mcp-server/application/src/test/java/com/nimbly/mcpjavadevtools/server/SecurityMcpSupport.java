package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Security inputs and Artifact assertions over the shared packaged CDE driver. */
abstract class SecurityMcpSupport extends ArtifactMcpSupport {
    static final String PROFILE = "security";
    static final String OPERATION = "security_suite.execute_plan";

    static McpServerProcess start(Path workspace) throws Exception {
        McpServerProcess server = McpServerProcess.startWithEnvironment(jarPath(), workspace,
                Map.of("SECURITY_IT_TOKEN", "", "BAD_SECURITY_IT_TOKEN", ""), "--workspace-root=" + workspace);
        initialize(server);
        return server;
    }

    static ObjectNode context(Path workspace, String... plans) {
        ObjectNode project = JSON.createObjectNode();
        ObjectNode root = project.putArray("workspaces").addObject().put("projectRoot", workspace.toString());
        root.put("envFile", ".env");
        root.putObject("defaults").putObject("orchestrator")
                .put("resumePollMax", 1).put("resumePollIntervalMs", 10).put("resumePollTimeoutMs", 100);
        root.putObject("variables").putObject("contextBindings").put("actor-token", "SECURITY_IT_TOKEN");
        ObjectNode profile = root.putArray("executionProfiles").addObject().put("executionProfile", PROFILE)
                .put("suiteType", "security").put("executionPolicy", "stop_on_fail");
        for (int i = 0; i < plans.length; i++) {
            profile.withArray("plans").addObject().put("order", i + 1).put("planName", plans[i]).put("onFail", "inherit");
        }
        return project;
    }

    static void saveContext(McpServerProcess server, ObjectNode project) throws Exception {
        ok(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project));
    }

    static ObjectNode generatedPlan(String url) {
        ObjectNode plan = plan("security", url);
        ObjectNode contract = contract(plan);
        URI base = URI.create(url);
        ObjectNode boundary = contract.putObject("targetBoundary").put("environment", "local-ci").put("baseUrl", url);
        boundary.putArray("allowedHosts").add(base.getHost());
        boundary.putArray("allowedPorts").add(base.getPort());
        entry(plan).withObject("transport").put("path", "/safe");
        contract.putArray("authenticationProfiles").addObject().put("id", "actor").put("kind", "anonymous");
        contract.putObject("exhaustiveness").put("mode", "finite_matrix").put("requireAllCases", true).put("onIncomplete", "blocked");
        contract.putObject("safetyPolicy").put("maxConcurrency", 1).put("maxRequestsPerSecond", 100)
                .put("maxDurationMs", 120000).put("destructivePayloads", "forbidden")
                .put("stateMutation", "test-tenant-only").put("cleanupRequired", true);
        contract.putObject("verdictPolicy").put("requireExhaustiveCompletion", true).put("blockedCountsAs", "fail")
                .putArray("failOnSeverity").add("critical").add("high").add("medium").add("low").add("info");
        return plan;
    }

    static ObjectNode contract(ObjectNode plan) {
        return (ObjectNode) plan.path("contract");
    }

    static ObjectNode entry(ObjectNode plan) {
        return (ObjectNode) contract(plan).path("entrypoints").get(0);
    }

    static void authenticated(ObjectNode plan, String kind) {
        ((ObjectNode) contract(plan).path("authenticationProfiles").get(0))
                .put("kind", kind).put("credentialRef", "actor-token");
    }

    static void packs(ObjectNode plan, String... refs) {
        var values = contract(plan).putObject("securityKnowledge").putArray("packRefs");
        for (String ref : refs) {
            values.add(ref);
        }
    }

    static ObjectNode custom(ObjectNode plan, String field, String id, String outcome) {
        ObjectNode attack = contract(plan).withArray(field).addObject().put("id", id).put("category", "authorization")
                .put("entrypointRef", "health").put("authenticationProfileRef", "actor");
        attack.putObject("baseline").putObject("expect").put("outcome", "allow");
        attack.putObject("attack").putObject("expect").put("outcome", outcome);
        return attack;
    }

    static void save(McpServerProcess server, String name, ObjectNode plan) throws Exception {
        ok(server, "security_plan.upsert", Map.of("projectName", PROJECT, "planName", name, "payload", plan));
        ok(server, "security_plan.validate", Map.of("projectName", PROJECT, "planName", name));
        JsonNode loaded = ok(server, "security_plan.read", Map.of("projectName", PROJECT, "planName", name,
                "query", Map.of("select", List.of("metadata", "contract"))));
        assertThat(loaded.path("artifact").path("contract").path("securityMode"))
                .isEqualTo(contract(plan).path("securityMode"));
    }

    static Map<String, Object> selector(String plan, String run) {
        return Map.of("projectName", PROJECT, "executionProfile", PROFILE, "planName", plan, "suiteRunId", run);
    }

    static JsonNode run(McpServerProcess server, String plan, String run) throws Exception {
        JsonNode response = execute(server, OPERATION, selector(plan, run), true);
        assertThat(response.path("status").asText()).as(response.toPrettyString()).isEqualTo("succeeded");
        return response.path("result");
    }

    static Path runFile(Path workspace, String plan, String run) {
        return workspace.resolve(".mcpjvm/" + PROJECT + "/plans/security/" + plan + "/runs/" + run + "-1/execution.result.json");
    }

    static JsonNode assertRun(McpServerProcess server, Path workspace, String plan, String run, String status) throws Exception {
        Path file = runFile(workspace, plan, run);
        assertThat(file).isRegularFile();
        JsonNode persisted = JSON.readTree(file.toFile());
        assertThat(persisted.path("status").asText()).as(persisted.toPrettyString()).isEqualTo(status);
        JsonNode read = ok(server, "run_result.read", Map.of("projectName", PROJECT, "suiteType", "security",
                "planName", plan, "runId", run + "-1"));
        assertThat(read.path("artifact").path("status")).isEqualTo(persisted.path("status"));
        assertThat(read.path("artifact").path("details").path("coverage"))
                .isEqualTo(persisted.path("details").path("coverage"));
        ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
        JsonNode rows = query(server, "run_state", Map.of("runId", run + "-1")).path("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("status").asText()).isEqualTo(status);
        assertThat(persisted.toString()).doesNotContain(SECRET);
        return persisted;
    }

    static void completed(JsonNode result, String status, int count) {
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("completed");
        assertThat(result.path("details").path("runStatus").asText()).isEqualTo(status);
        assertThat(result.path("details").path("coverage").path("complete").asBoolean()).isTrue();
        assertThat(result.path("details").path("coverage").path("plannedCount").asInt()).isEqualTo(count);
    }

    static void assertBlocked(JsonNode result, String reason) {
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("blocked");
        assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(result.path("details").path("runStatus").asText()).isNotEqualTo("pass");
    }
}
