package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("security")
@Timeout(240)
class McpServerSecuritySidecarIT extends SecurityMcpSupport {
    @Test
    void orphanedOrUnselectedRuntimeTargetsNeverPassWithoutProbeEvidence() throws Exception {
        var workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, "orphan", "unselected", "unavailable"));
            for (String name : List.of("orphan", "unselected", "unavailable")) {
                ObjectNode plan = generatedPlan(target.url(""));
                contract(plan).put("securityMode", "sidecar_assisted");
                packs(plan, "web-api-core");
                custom(plan, "attackProfiles", "attack", "allow");
                ObjectNode runtime = contract(plan).putArray("runtimeTargets").addObject().put("id", "controller")
                        .put("entrypointRef", "health").put("probeBaseUrl", "http://127.0.0.1:1")
                        .put("strictLineKey", "com.example.Fixture#read:1");
                if (name.equals("orphan")) {
                    runtime.put("entrypointRef", "missing");
                } else if (name.equals("unselected")) {
                    contract(plan).withArray("entrypoints").add(entry(plan).deepCopy().put("id", "second"));
                    runtime.put("entrypointRef", "second");
                }
                save(server, name, plan);
                JsonNode result = run(server, name, name);
                String reason = name.equals("orphan") ? "security_contract_runtime_targets_invalid"
                        : "security_sidecar_coverage_incomplete";
                assertBlocked(result, reason);
                if (!name.equals("orphan")) {
                    assertCaseReason(result, name.equals("unselected") ? "security_sidecar_runtime_target_ambiguous"
                            : "security_sidecar_probe_reset_failed");
                    assertThat(result.path("details").path("coverage").path("complete").asBoolean()).isFalse();
                    assertThat(result.path("details").path("findings")).isEmpty();
                }
                target.assertNoRequest();
                assertThat(assertRun(server, workspace, name, name, "blocked").path("reasonCode").asText()).isEqualTo(reason);
                JavaItScenario.observe(OPERATION, "ASSERTED", "runtime_selection_" + name);
            }
        }
    }

    @Test
    void disappearingRealSidecarCannotManufactureAForbiddenLineFinding() throws Exception {
        java.util.concurrent.CountDownLatch attackEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        try (JvmProbeScenario s = JvmProbeScenario.start();
                TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
                    int count = requests.incrementAndGet();
                    if (count == 2) {
                        attackEntered.countDown();
                        try {
                            if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new java.io.IOException("fixture release deadline exceeded");
                            }
                        } catch (InterruptedException interruption) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException("fixture interrupted");
                        }
                    }
                    TransportHttpTarget.respond(exchange, count == 1 ? 200 : 403, "{}");
                })) {
            s.attach();
            saveContext(s.server, context(s.workspace, "disappeared"));
            ObjectNode plan = sidecarPlan(s, s.postKey());
            ((ObjectNode) contract(plan).path("authenticationProfiles").get(0)).put("kind", "anonymous").remove("credentialRef");
            contract(plan).withObject("targetBoundary").put("baseUrl", target.url(""))
                    .putArray("allowedPorts").add(java.net.URI.create(target.url("")).getPort());
            entry(plan).withObject("transport").put("path", "/safe");
            ObjectNode attack = custom(plan, "attackProfiles", "attack", "deny");
            attack.withObject("attack").withObject("expect").putArray("mustNotHitRuntimeTargets").add("controller");
            save(s.server, "disappeared", plan);
            s.server.send(executeRequest(910, OPERATION, selector("disappeared", "disappeared"), true));
            assertThat(attackEntered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            s.target.close();
            release.countDown();
            JsonNode result = toolPayload(s.server.responseFor(910, java.time.Duration.ofSeconds(30))).path("result");
            assertBlocked(result, "security_sidecar_coverage_incomplete");
            assertCaseReason(result, "security_sidecar_probe_status_unavailable");
            assertThat(result.path("details").path("findings")).isEmpty();
            assertThat(requests).hasValue(2);
            target.received();
            target.received();
            target.assertNoRequest();
            assertRun(s.server, s.workspace, "disappeared", "disappeared", "blocked");
        } finally {
            release.countDown();
        }
    }

    @Test
    void realFreshRequiredAndForbiddenHitsDistinguishPassInternalAndCorroboratedFindings() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            Files.writeString(s.workspace.resolve(".env"), "SECURITY_IT_TOKEN=alice-token\n");
            saveContext(s.server, context(s.workspace, "passed", "foreign", "internal", "nonfailing", "registry"));
            ObjectNode registry = McpServerArtifactConfigIT.registry(s.workspace);
            ((ObjectNode) registry.path("profiles").path("local").path("probes").path("one")).put("baseUrl", s.baseUrl);
            ok(s.server, "probe_config.upsert", Map.of("payload", registry));
            assertThat(artifact(s.server, "probe_config.reload", Map.of()).path("status").asText()).isEqualTo("reloaded");
            String key = s.postKey();
            for (String name : List.of("passed", "foreign", "internal", "nonfailing", "registry")) {
                ObjectNode plan = sidecarPlan(s, key);
                boolean foreign = name.equals("foreign") || name.equals("nonfailing");
                boolean passing = name.equals("passed") || name.equals("registry") || name.equals("nonfailing");
                if (name.equals("nonfailing")) {
                    contract(plan).withObject("verdictPolicy").putArray("failOnSeverity");
                } else if (name.equals("registry")) {
                    ObjectNode runtime = (ObjectNode) contract(plan).path("runtimeTargets").get(0);
                    runtime.remove("probeBaseUrl");
                    runtime.put("probeId", "one");
                }
                ObjectNode attack = custom(plan, "attackProfiles", name, "deny");
                attack.withObject("baseline").putObject("pathParameters").put("id", "101");
                attack.withObject("baseline").withObject("expect").putArray("mustHitRuntimeTargets").add("controller");
                attack.withObject("attack").putObject("pathParameters").put("id", foreign ? "102" : "99999");
                attack.withObject("attack").withObject("expect")
                        .putArray(name.equals("internal") ? "mustNotHitRuntimeTargets" : "mustHitRuntimeTargets").add("controller");
                save(s.server, name, plan);
                JsonNode result = run(s.server, name, name);
                completed(result, passing ? "pass" : "fail", 1);
                assertThat(s.status(key).path("hitCount").asLong()).isPositive();
                assertThat(s.status(key).path("hitCount").asLong()).as("attack reset removes historical baseline hits").isEqualTo(1);
                JsonNode row = result.path("details").path("cases").get(0);
                assertThat(row.path("evidence")).hasSize(2);
                assertThat(row.path("evidence").get(0).path("statusCode").asInt()).isEqualTo(200);
                assertThat(row.path("evidence").get(1).path("statusCode").asInt()).isEqualTo(foreign ? 200 : 404);
                if (name.equals("passed") || name.equals("registry")) {
                    assertThat(row.path("outcome").asText()).isEqualTo("passed");
                    assertThat(result.path("details").path("findings")).isEmpty();
                } else {
                    assertThat(row.path("outcome").asText()).isEqualTo("confirmed");
                    assertThat(row.path("proofClassification").asText())
                            .isEqualTo(foreign ? "corroborated_external" : "internal");
                }
                JsonNode persisted = assertRun(s.server, s.workspace, name, name, passing ? "pass" : "fail");
                assertThat(persisted.path("details").path("cases").get(0)).isEqualTo(row);
                JsonNode runtime = row.path("runtimeEvidence");
                assertThat(runtime).as("historical Probe observations must survive in the run Artifact").hasSize(2);
                assertThat(runtime.get(0).path("strictLineKey").asText()).isEqualTo(key);
                assertThat(runtime.get(1).path("phase").asText()).isEqualTo("attack");
                assertThat(runtime.get(1).path("lineHit").asBoolean()).isTrue();
                assertThat(runtime.get(1).path("hitCount").asLong()).isEqualTo(1);
                JavaItScenario.observe(OPERATION, "ASSERTED", "fresh_" + name + "_" + key);
            }
            s.deactivate();
        }
    }

    @Test
    void unavailableSidecarMissingHitsAndInvalidReferencesRemainIncompleteAndRecoverOnNewRun() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            Files.writeString(s.workspace.resolve(".env"), "SECURITY_IT_TOKEN=alice-token\n");
            saveContext(s.server, context(s.workspace, "unavailable", "no-hit", "reference", "profile", "unsupported", "invalid-key"));
            String key = s.postKey();
            ObjectNode plan = sidecarPlan(s, key);
            ObjectNode attack = custom(plan, "attackProfiles", "attack", "allow");
            attack.withObject("baseline").putObject("pathParameters").put("id", "101");
            attack.withObject("baseline").withObject("expect").putArray("mustHitRuntimeTargets").add("controller");
            attack.withObject("attack").putObject("pathParameters").put("id", "101");
            save(s.server, "unavailable", plan);
            JsonNode blocked = run(s.server, "unavailable", "before-attach");
            assertBlocked(blocked, "security_sidecar_coverage_incomplete");
            assertCaseReason(blocked, "security_sidecar_probe_reset_failed");
            assertRun(s.server, s.workspace, "unavailable", "before-attach", "blocked");
            s.attach();
            completed(run(s.server, "unavailable", "after-attach"), "pass", 1);
            assertThat(s.status(key).path("hitCount").asLong()).isPositive();
            for (String name : List.of("no-hit", "reference", "profile", "unsupported", "invalid-key")) {
                ObjectNode altered = plan.deepCopy();
                if (name.equals("no-hit")) {
                    entry(altered).withObject("transport").put("path", "/actuator/health");
                } else if (name.equals("reference") || name.equals("profile")) {
                    ((ObjectNode) contract(altered).path("attackProfiles").get(0))
                            .put(name.equals("reference") ? "entrypointRef" : "authenticationProfileRef", "missing");
                } else if (name.equals("unsupported")) {
                    entry(altered).withObject("transport").put("type", "kafka");
                } else {
                    ((ObjectNode) contract(altered).path("runtimeTargets").get(0)).put("strictLineKey", "invalid");
                }
                save(s.server, name, altered);
                JsonNode result = run(s.server, name, name);
                if (name.equals("unsupported")) {
                    completed(result, "pass", 1);
                    assertThat(result.path("details").path("cases").get(0).path("outcome").asText()).isEqualTo("not_applicable");
                } else {
                    assertBlocked(result, "security_sidecar_coverage_incomplete");
                    assertCaseReason(result, name.equals("no-hit") ? "security_sidecar_required_line_not_hit"
                            : name.equals("invalid-key") ? "security_sidecar_probe_reset_failed" : "security_sidecar_reference_missing");
                    assertRun(s.server, s.workspace, name, name, "blocked");
                }
            }
            s.deactivate();
        }
    }

    private ObjectNode sidecarPlan(JvmProbeScenario s, String key) {
        ObjectNode plan = generatedPlan("http://127.0.0.1:" + s.target.port());
        contract(plan).put("securityMode", "sidecar_assisted");
        authenticated(plan, "bearer");
        entry(plan).withObject("transport").put("path", "/api/v1/posts/{id}");
        contract(plan).putArray("runtimeTargets").addObject().put("id", "controller").put("entrypointRef", "health")
                .put("probeBaseUrl", s.baseUrl).put("strictLineKey", key);
        return plan;
    }

    private void assertCaseReason(JsonNode result, String reason) {
        assertThat(result.path("details").path("coverage").path("complete").asBoolean()).isFalse();
        assertThat(result.path("details").path("cases").get(0).path("reasonCode").asText()).isEqualTo(reason);
    }
}
