package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("security")
@Timeout(180)
class McpServerSecurityValidationIT extends SecurityMcpSupport {
    @Test
    void invalidContractsBlockBeforeTargetAndPersistDeterministicReasons() throws Exception {
        Path workspace = JavaItScenario.workspace();
        String[] names = {"mode", "entrypoints", "profiles", "environment", "base", "host", "port", "pack", "attacks", "targets", "key",
                "concurrency", "rate", "duration", "destructive", "cleanup", "severity", "exhaustive", "scheme", "userinfo", "template",
                "duplicate-entry", "duplicate-auth", "invalid-kind", "duplicate-custom", "duplicate-runtime", "duplicate-attacks"};
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, names));
            for (String name : names) {
                ObjectNode plan = generatedPlan(target.url(""));
                String reason = invalidate(plan, name);
                save(server, name, plan);
                JsonNode result = run(server, name, name);
                assertBlocked(result, reason);
                target.assertNoRequest();
                JsonNode persisted = assertRun(server, workspace, name, name, "blocked");
                assertThat(persisted.path("reasonCode").asText()).isEqualTo(reason);
            }
        }
    }

    private String invalidate(ObjectNode plan, String name) {
        ObjectNode c = contract(plan);
        switch (name) {
            case "mode" -> c.put("securityMode", "unknown");
            case "entrypoints" -> c.putArray("entrypoints");
            case "profiles" -> c.putArray("authenticationProfiles");
            case "environment" -> c.withObject("targetBoundary").put("environment", "external");
            case "base" -> c.withObject("targetBoundary").put("baseUrl", "invalid uri");
            case "host" -> c.withObject("targetBoundary").putArray("allowedHosts").add("localhost");
            case "port" -> c.withObject("targetBoundary").putArray("allowedPorts").add(1);
            case "pack" -> packs(plan, "absent@9.0.0");
            case "concurrency" -> c.withObject("safetyPolicy").put("maxConcurrency", 0);
            case "rate" -> c.withObject("safetyPolicy").put("maxRequestsPerSecond", 0);
            case "duration" -> c.withObject("safetyPolicy").put("maxDurationMs", -1);
            case "destructive" -> c.withObject("safetyPolicy").put("destructivePayloads", "allowed");
            case "cleanup" -> c.withObject("safetyPolicy").put("cleanupRequired", false);
            case "severity" -> c.withObject("verdictPolicy").putArray("failOnSeverity").add("invalid");
            case "exhaustive" -> c.withObject("exhaustiveness").put("requireAllCases", false);
            case "scheme" -> c.withObject("targetBoundary").put("baseUrl", "file:///fixture");
            case "userinfo" -> c.withObject("targetBoundary").put("baseUrl", "http://name@127.0.0.1:1");
            case "template" -> entry(plan).withObject("transport").put("path", "/resource/{id}");
            case "duplicate-entry" -> c.withArray("entrypoints").add(entry(plan).deepCopy());
            case "duplicate-auth" -> c.withArray("authenticationProfiles").add(c.path("authenticationProfiles").get(0).deepCopy());
            case "invalid-kind" -> ((ObjectNode) c.path("authenticationProfiles").get(0)).put("kind", "unsupported");
            case "duplicate-custom" -> {
                ObjectNode custom = custom(plan, "customCases", "duplicate", "allow");
                c.withArray("customCases").add(custom.deepCopy());
            }
            case "duplicate-runtime", "duplicate-attacks" -> {
                c.put("securityMode", "sidecar_assisted");
                ObjectNode attack = custom(plan, "attackProfiles", "duplicate", "allow");
                ObjectNode target = c.putArray("runtimeTargets").addObject().put("id", "controller").put("entrypointRef", "health")
                        .put("probeBaseUrl", "http://127.0.0.1:1").put("strictLineKey", "example.Controller#get:10");
                if (name.equals("duplicate-runtime")) {
                    c.withArray("runtimeTargets").add(target.deepCopy());
                } else {
                    c.withArray("attackProfiles").add(attack.deepCopy());
                }
            }
            default -> {
                c.put("securityMode", "sidecar_assisted");
                if (!name.equals("attacks")) {
                    custom(plan, "attackProfiles", "attack", "deny");
                }
                if (name.equals("key")) {
                    c.putArray("runtimeTargets").addObject().put("id", "controller").put("entrypointRef", "health");
                }
            }
        }
        return switch (name) {
            case "mode" -> "security_mode_invalid";
            case "entrypoints" -> "security_contract_entrypoints_invalid";
            case "profiles" -> "security_contract_authentication_profiles_invalid";
            case "environment", "base", "scheme", "userinfo" -> "security_contract_target_boundary_invalid";
            case "concurrency", "rate", "duration", "destructive", "cleanup" -> "security_contract_safety_policy_invalid";
            case "severity" -> "security_contract_verdict_policy_invalid";
            case "exhaustive" -> "security_contract_exhaustiveness_invalid";
            case "template" -> "security_blackbox_fixture_unresolved";
            case "duplicate-entry" -> "security_contract_entrypoints_invalid";
            case "duplicate-auth", "invalid-kind" -> "security_contract_authentication_profiles_invalid";
            case "duplicate-runtime" -> "security_contract_runtime_targets_invalid";
            case "duplicate-custom", "duplicate-attacks" -> "security_contract_attack_profiles_invalid";
            case "host", "port" -> "security_target_boundary_violation";
            case "pack" -> "security_knowledge_pack_unavailable";
            case "attacks" -> "security_contract_attack_profiles_invalid";
            case "targets" -> "security_contract_sidecar_runtime_target_required";
            default -> "security_contract_runtime_targets_invalid";
        };
    }

    @Test
    void cdeDiscoveryConfirmationAndTrustedSelectorsPreventUnownedExecution() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            server.send(request(800, "tools/call", Map.of("name", "operation_catalog", "arguments", Map.of("api", "security_suite"))));
            assertThat(toolPayload(server.responseFor(800)).toString()).contains(OPERATION);
            server.send(request(801, "tools/call", Map.of("name", "operation_describe", "arguments", Map.of("operationId", OPERATION))));
            JsonNode description = toolPayload(server.responseFor(801));
            assertThat(description.path("operation").path("safety").path("confirmationRequired").asBoolean()).isTrue();
            assertThat(description.path("operation").path("safety").path("cancellationSupported").asBoolean()).isFalse();
            assertThat(description.path("operation").path("inputSchema").path("additionalProperties").asBoolean()).isFalse();
            saveContext(server, context(workspace, "valid", "absent"));
            save(server, "valid", generatedPlan(target.url("")));
            JsonNode confirmation = execute(server, OPERATION, selector("valid", "confirm"), false);
            assertThat(confirmation.path("status").asText()).isEqualTo("confirmation_required");
            assertThat(runFile(workspace, "valid", "confirm")).doesNotExist();
            JsonNode invalid = execute(server, OPERATION, Map.of("projectName", PROJECT), true);
            assertThat(invalid.path("status").asText()).isEqualTo("invalid_input");
            assertBlocked(run(server, "../escape", "invalid"), "direct_suite_selector_invalid");
            assertBlocked(run(server, "absent", "missing"), "direct_suite_plan_unavailable");
            assertBlocked(run(server, "unlisted", "missing"), "direct_suite_plan_unavailable");
            target.assertNoRequest();
        }
    }

    @Test
    void unexpectedBaselineAttackAndMissingReferencesNeverBecomeCleanCoverage() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            String query = exchange.getRequestURI().getQuery();
            TransportHttpTarget.respond(exchange, query == null ? 200 : query.equals("q=deny") ? 403 : 503, "{}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "baseline", "attack", "reference", "profile", "unsupported"));
            for (String name : List.of("baseline", "attack", "reference", "profile", "unsupported")) {
                ObjectNode plan = generatedPlan(target.url(""));
                packs(plan, "web-api-core");
                ObjectNode custom = custom(plan, "customCases", "custom", "deny");
                if (name.equals("baseline")) {
                    custom.withObject("baseline").putObject("query").put("q", "deny");
                } else if (name.equals("attack")) {
                    custom.withObject("attack").putObject("query").put("q", "error");
                } else if (name.equals("reference")) {
                    custom.put("entrypointRef", "missing");
                } else if (name.equals("profile")) {
                    custom.put("authenticationProfileRef", "missing");
                } else {
                    entry(plan).withObject("transport").put("type", "kafka");
                }
                save(server, name, plan);
                JsonNode result = run(server, name, name);
                if (name.equals("unsupported")) {
                    completed(result, "pass", 2);
                    for (JsonNode row : result.path("details").path("cases")) {
                        assertThat(row.path("outcome").asText()).isEqualTo("not_applicable");
                    }
                    target.assertNoRequest();
                } else {
                    assertBlocked(result, name.equals("attack") ? "security_blackbox_attack_unexpected"
                            : name.equals("baseline") ? "security_blackbox_baseline_unexpected" : "security_blackbox_reference_missing");
                    assertThat(result.path("details").path("coverage").path("complete").asBoolean()).isFalse();
                    int count = name.equals("attack") ? 3 : name.equals("baseline") ? 2 : 1;
                    for (int i = 0; i < count; i++) {
                        target.received();
                    }
                    target.assertNoRequest();
                    assertRun(server, workspace, name, name, "blocked");
                }
            }
        }
    }
}
