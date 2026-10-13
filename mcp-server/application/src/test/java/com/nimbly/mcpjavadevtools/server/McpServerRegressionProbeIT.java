package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("regression")
@Timeout(240)
class McpServerRegressionProbeIT extends RegressionMcpSupport {
    @Test
    void pinnedRegistryAndAutomaticSourceRoutesRequireFreshRealLineHits() throws Exception {
        try (JvmProbeScenario s = JvmProbeScenario.start()) {
            s.attach();
            String url = "http://127.0.0.1:" + s.target.port();
            saveContext(s.server, context(s.workspace, url, "pinned", "automatic", "no-hit", "invalid", "disabled", "explicit", "unavailable"));
            ObjectNode registry = McpServerArtifactConfigIT.registry(s.workspace);
            ((ObjectNode) registry.path("profiles").path("local").path("probes").path("one")).put("baseUrl", s.baseUrl);
            ok(s.server, "probe_config.upsert", Map.of("payload", registry));
            assertThat(artifact(s.server, "probe_config.reload", Map.of()).path("status").asText()).isEqualTo("reloaded");
            String key = s.postKey();
            ObjectNode plan = probePlan(url + "/api/v1/posts/101", key);
            save(s.server, "pinned", plan);
            assertLineHit(run(s.server, "pinned", "registry"), key);
            assertRun(s.server, s.workspace, "pinned", "registry", "pass");
            assertExplicitAndUnavailableRoutes(s, url, key);
            Path source = repositoryRoot().resolve(JvmProbeScenario.SOURCE + JvmProbeScenario.CONTROLLER.replace('.', '/') + ".java");
            Path copy = s.workspace.resolve("src/main/java/" + JvmProbeScenario.CONTROLLER.replace('.', '/') + ".java");
            Files.createDirectories(copy.getParent());
            Files.copy(source, copy);
            ((ObjectNode) plan.path("metadata").path("execution")).put("pinStrictProbeKey", false);
            ((ObjectNode) contract(plan).path("targets").get(0).path("runtimeVerification")).remove("strictProbeKey");
            ((ObjectNode) contract(plan).path("targets").get(0)).putObject("selectors")
                    .put("fqcn", JvmProbeScenario.CONTROLLER).put("method", "getPost").put("sourceRoot", "src/main/java");
            save(s.server, "automatic", plan);
            assertLineHit(run(s.server, "automatic", "source"), key);
            assertNoHitAndDisabled(s, url, key);
            ObjectNode invalid = probePlan(url + "/api/v1/posts/101", "not-a-line-key");
            save(s.server, "invalid", invalid);
            assertThat(run(s.server, "invalid", "invalid-key").path("reasonCode").asText()).isEqualTo("strict_probe_key_invalid");
            s.deactivate();
        }
    }

    private void assertExplicitAndUnavailableRoutes(JvmProbeScenario s, String url, String key) throws Exception {
        ObjectNode plan = probePlan(url + "/api/v1/posts/101", key);
        ObjectNode runtime = (ObjectNode) contract(plan).path("targets").get(0).path("runtimeVerification");
        runtime.remove("probeId");
        runtime.put("probeBaseUrl", s.baseUrl);
        save(s.server, "explicit", plan);
        JsonNode hit = run(s.server, "explicit", "explicit").path("details").path("probeVerification").path("lineHits").get(0);
        assertThat(hit.path("strictLineKey").asText()).isEqualTo(key);
        assertThat(hit.path("lineHit").asBoolean()).isTrue();
        runtime.put("probeBaseUrl", "http://127.0.0.1:" + freePort());
        save(s.server, "unavailable", plan);
        JsonNode unavailable = run(s.server, "unavailable", "unavailable");
        assertThat(unavailable.path("reasonCode").asText()).isEqualTo("probe_verification_reset_failed");
        assertThat(unavailable.path("details").path("steps")).isEmpty();
    }

    private void assertNoHitAndDisabled(JvmProbeScenario s, String url, String key) throws Exception {
        ObjectNode noHit = probePlan(url + "/actuator/health", key);
        save(s.server, "no-hit", noHit);
        JsonNode failed = run(s.server, "no-hit", "not-observed");
        assertThat(failed.path("reasonCode").asText()).isEqualTo("probe_verification_line_not_hit");
        assertThat(failed.path("details").path("probeVerification").path("strictLineKey").asText()).isEqualTo(key);
        assertRun(s.server, s.workspace, "no-hit", "not-observed", "fail");
        ((ObjectNode) noHit.path("metadata").path("execution")).put("probeVerification", false);
        save(s.server, "disabled", noHit);
        JsonNode disabled = run(s.server, "disabled", "optional-disabled");
        assertThat(disabled.path("details").path("runStatus").asText()).isEqualTo("pass");
        assertThat(disabled.path("details").has("probeVerification")).isFalse();
    }

    private ObjectNode probePlan(String url, String key) {
        ObjectNode plan = generatedPlan(url);
        ((ObjectNode) plan.path("metadata").path("execution")).put("probeVerification", true).put("pinStrictProbeKey", true);
        ((ObjectNode) contract(plan).path("targets").get(0)).putObject("runtimeVerification")
                .put("strictProbeKey", key).put("probeId", "one");
        http(step(plan, 0)).putObject("headers").put("Authorization", "Bearer alice-token")
                .put("x-run-as-tenant", "fixture-tenant").put("x-run-as-user", "alice");
        return plan;
    }

    private void assertLineHit(JsonNode result, String key) {
        assertThat(result.path("details").path("runStatus").asText()).as(result.toPrettyString()).isEqualTo("pass");
        JsonNode hit = result.path("details").path("probeVerification").path("lineHits").get(0);
        assertThat(hit.path("strictLineKey").asText()).isEqualTo(key);
        assertThat(hit.path("probeId").asText()).isEqualTo("one");
        assertThat(hit.path("lineHit").asBoolean()).isTrue();
    }
}
