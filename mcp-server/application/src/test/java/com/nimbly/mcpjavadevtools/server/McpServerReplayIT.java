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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("orchestration")
@Timeout(180)
class McpServerReplayIT extends ReplayMcpSupport {
    @ParameterizedTest
    @CsvSource({"profile,false", "plan,false", "request,false", "plan,true"})
    void postmanReplaysProvidedContextWithPrecedenceAndRedaction(String variant, boolean includeSecrets) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            providedContextInputs(server, workspace, target.url(""), variant);
            assertStatus(orchestrate(server, PROFILE, "source", 0), "pass", "ok");
            assertThat(target.received().uri()).isEqualTo(variant.equals("profile") ? "/context" : "/plan/context");
            var arguments = new java.util.LinkedHashMap<String, Object>(Map.of("projectName", PROJECT,
                    "executionProfile", PROFILE, "exportId", "provided", "mode", "postman", "includeResolvedSecrets", includeSecrets));
            if (variant.equals("request")) {
                arguments.put("contextValues", Map.of("apiBaseUrl", target.url("/request"), "label", "request"));
            } else if (includeSecrets) {
                arguments.put("contextValues", Map.of("password", "disposable-export-655"));
            }
            Path directory = export(server, workspace, arguments);
            JsonNode environment = JSON.readTree(directory.resolve("environment.postman.json").toFile());
            String expectedLabel = variant.equals("request") ? "request" : variant;
            assertThat(environmentValue(environment, "LABEL").path("value").asText()).isEqualTo(expectedLabel);
            assertThat(environmentValue(environment, "PUBLIC_INPUT").path("type").asText()).isEqualTo("secret");
            assertThat(environmentValue(environment, "PUBLIC_INPUT").path("value").asText())
                    .isEqualTo(includeSecrets ? "disposable-export-655" : "");
            if (!includeSecrets) {
                assertThat(packageText(directory)).doesNotContain("disposable-profile-655", "disposable-plan-655");
            }
            runPackage(directory, replayCommand("postman", directory), Map.of());
            var received = target.received();
            String prefix = variant.equals("profile") ? "" : "/" + variant;
            assertThat(received.uri()).isEqualTo(prefix + "/context");
            assertThat(received.header("X-Label")).isEqualTo(expectedLabel);
            target.assertNoRequest();
        }
    }

    private void providedContextInputs(McpServerProcess server, Path workspace, String base, String variant) throws Exception {
        Files.writeString(workspace.resolve("fixture.env"), "ReplayOrigin=http://unreachable.invalid\nLABEL=environment\nPUBLIC_INPUT=disposable-profile-655\n");
        ObjectNode project = context(workspace, base, "context");
        profile(project).withObject("providedContext").put("label", "profile").put("password", "disposable-profile-655");
        if (!variant.equals("profile")) {
            ((ObjectNode) profile(project).path("plans").get(0)).putObject("providedContext")
                    .put("apiBaseUrl", base + "/plan").put("label", "plan").put("password", "disposable-plan-655");
        }
        root(project).put("envFile", "fixture.env").putObject("variables").putObject("contextBindings")
                .put("apiBaseUrl", "ReplayOrigin").put("password", "PUBLIC_INPUT");
        saveContext(server, project);
        ObjectNode plan = generatedPlan("${apiBaseUrl}/context");
        http(step(plan, 0)).putObject("headers").put("X-Label", "${label}");
        save(server, "context", plan);
    }

    private JsonNode environmentValue(JsonNode environment, String key) {
        return java.util.stream.StreamSupport.stream(environment.path("values").spliterator(), false)
                .filter(value -> key.equals(value.path("key").asText())).findFirst().orElseThrow();
    }

    @Test
    void postmanKeepsPlanOverridesScopedAndReturnsToProfileDefaults() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "one", "two", "three");
            profile(project).withObject("providedContext").put("label", "profile");
            for (int index = 0; index < 2; index++) {
                ((ObjectNode) profile(project).path("plans").get(index)).putObject("providedContext").put("label", "plan-" + index);
            }
            saveContext(server, project);
            for (String name : new String[] {"one", "two", "three"}) {
                ObjectNode plan = generatedPlan("${apiBaseUrl}/" + name);
                http(step(plan, 0)).putObject("headers").put("X-Label", "${label}");
                save(server, name, plan);
            }
            Path directory = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "scoped-context", "mode", "postman"));
            runPackage(directory, replayCommand("postman", directory), Map.of());
            for (String label : new String[] {"plan-0", "plan-1", "profile"}) {
                assertThat(target.received().header("X-Label")).isEqualTo(label);
            }
            target.assertNoRequest();
        }
    }

    @ParameterizedTest
    @CsvSource({"provision,postman_provisioning_not_supported", "native,postman_script_conversion_required",
            "javascript,postman_script_invalid_format"})
    void postmanRejectsPrerequisitesItCannotReplay(String kind, String reason) throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve("setup.js"), "console.log('plain node script');\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "first");
            ObjectNode script = root(project).putArray("scripts").addObject().put("name", "setup")
                    .put("command", kind.equals("provision") ? "docker" : "node");
            var args = script.putArray("args");
            if (kind.equals("javascript")) {
                args.add("setup.js");
            } else {
                args.add(kind.equals("provision") ? "compose" : "-e").add("process.exit(0)");
            }
            profile(project).putArray("scriptRefs").add("setup");
            saveContext(server, project);
            save(server, "first", generatedPlan(target.url("/never")));
            JsonNode response = execute(server, "execution_profile_export.export", Map.of("projectName", PROJECT,
                    "executionProfile", PROFILE, "exportId", "blocked", "mode", "postman"), true).path("result");
            assertThat(response.path("reasonCode").asText()).as(response.toPrettyString()).isEqualTo(reason);
            target.assertNoRequest();
        }
    }

    @Test
    void postmanPreservesPrerequestScriptsAndExtractedContext() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Files.writeString(workspace.resolve("setup.js"), "pm.environment.set('SCRIPT_VALUE','from-postman-script');\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, "{\"id\":\"created-655\"}"));
                McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "create", "read");
            ((ObjectNode) profile(project).path("plans").get(1)).putObject("providedContext").put("createdId", "fallback-655");
            root(project).putArray("scripts").addObject().put("name", "setup").put("command", "node").putArray("args").add("setup.js");
            profile(project).putArray("scriptRefs").add("setup");
            saveContext(server, project);
            ObjectNode first = generatedPlan(target.url("/create"));
            http(step(first, 0)).putObject("headers").put("x-script", "${script_value}");
            step(first, 0).putArray("extract").addObject().put("from", "response.body.id").put("as", "createdId");
            save(server, "create", first);
            save(server, "read", generatedPlan(target.url("/items/${createdId}")));
            Path directory = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "postman-context", "mode", "postman"));
            Files.delete(workspace.resolve("setup.js"));
            runPackage(directory, replayCommand("postman", directory), Map.of());
            assertThat(target.received().header("x-script")).isEqualTo("from-postman-script");
            assertThat(target.received().uri()).isEqualTo("/items/created-655");
            target.assertNoRequest();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ps1", "sh", "postman"})
    void relocatedPackagesReplayOrderedRequestsWithRuntimeCredentials(String mode) throws Exception {
        Path workspace = JavaItScenario.workspace();
        String token = "Bearer disposable-replay-655";
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            Files.writeString(workspace.resolve("fixture.env"), "API_BASE_URL=" + target.url("") + "\nORCHESTRATION_TOKEN=" + token + "\n");
            ObjectNode project = context(workspace, target.url(""), "first", "second");
            root(project).put("envFile", "fixture.env").putObject("variables").putObject("contextBindings")
                    .put("apiBaseUrl", "API_BASE_URL").put("authorization", "ORCHESTRATION_TOKEN");
            saveContext(server, project);
            ObjectNode first = generatedPlan("${apiBaseUrl}/first?value=a%20b");
            http(step(first, 0)).put("method", "POST").put("body", "{\"value\":\"quotes ' and & spaces\"}")
                    .putObject("headers").put("Authorization", "${authorization}").put("Content-Type", "application/json");
            save(server, "first", first);
            save(server, "second", generatedPlan("${apiBaseUrl}/second"));
            Path original = export(server, workspace, Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "exportId", "portable", "mode", mode, "includeRuntimeStartup", false, "includeHealthcheckGate", false));
            assertThat(packageText(original)).doesNotContain(token, workspace.toString());
            String readme = Files.readString(original.resolve(mode.equals("postman") ? "README.postman.md" : "README." + mode + ".md"));
            assertThat(readme).contains("Prerequisites", mode.equals("postman") ? "environment.postman.json" : "project.env")
                    .doesNotContain("source_status=blocked", "run-execution-profile.postman", workspace.toString());
            assertThat(JSON.readTree(original.resolve("manifest.json").toFile()).path("workload")).hasSize(2);
            JsonNode read = ok(server, "execution_export.read", Map.of("projectName", PROJECT, "query", Map.of("exportId", "portable")));
            assertThat(read.toString()).contains("portable");
            Path relocated = relocate(original, workspace.resolve("relocated package"));
            Files.writeString(relocated.resolve("project.env"), "API_BASE_URL=\"" + target.url("")
                    + "\"\nORCHESTRATION_TOKEN=\"" + token + "\"\n");
            if (mode.equals("postman")) {
                supplyPostmanCredentials(relocated, token);
            }
            var command = replayCommand(mode, relocated);
            runPackage(relocated, command, Map.of());
            var observed = target.received();
            assertThat(observed.method()).isEqualTo("POST");
            assertThat(observed.uri()).isEqualTo("/first?value=a%20b");
            assertThat(observed.body()).isEqualTo("{\"value\":\"quotes ' and & spaces\"}");
            assertThat(observed.header("Authorization")).isEqualTo(token);
            assertThat(target.received().uri()).isEqualTo("/second");
            target.assertNoRequest();
            JavaItScenario.observe("execution_profile_export.export", "ASSERTED", "relocated_" + mode + "_replay");
        }
    }

    private void supplyPostmanCredentials(Path directory, String token) throws Exception {
        ObjectNode environment = (ObjectNode) JSON.readTree(directory.resolve("environment.postman.json").toFile());
        for (JsonNode value : environment.path("values")) {
            if (value.path("key").asText().equals("ORCHESTRATION_TOKEN")) {
                assertThat(value.path("value").asText()).isEmpty();
                ((ObjectNode) value).put("value", token);
            }
        }
        Files.writeString(directory.resolve("environment.postman.json"), environment.toPrettyString());
    }

    @Test
    void secretInclusionRequiresExplicitRequestAndSelectorsAndAliasAreSupported() throws Exception {
        Path workspace = JavaItScenario.workspace();
        String secret = "disposable-export-secret-655";
        Files.writeString(workspace.resolve("fixture.env"), "ORCHESTRATION_TOKEN=" + secret + "\n");
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "one", "two");
            root(project).put("envFile", "fixture.env").putObject("sessionExport").put("includeResolvedSecrets", true)
                    .put("includeRuntimeStartup", false).put("includeHealthcheckGate", false);
            saveContext(server, project);
            save(server, "one", generatedPlan(target.url("/one")));
            save(server, "two", generatedPlan(target.url("/two")));
            Path safe = export(server, workspace, Map.of("projectName", PROJECT, "exportId", "safe", "type", "ps1",
                    "contextBindings", Map.of("authorization", "ORCHESTRATION_TOKEN"), "contextValues", Map.of("authorization", secret)));
            assertThat(packageText(safe)).doesNotContain(secret);
            assertThat(JSON.readTree(safe.resolve("manifest.json").toFile()).path("includeResolvedSecrets").asBoolean()).isFalse();
            JsonNode manifestDefaults = JSON.readTree(safe.resolve("manifest.json").toFile());
            assertThat(manifestDefaults.path("includeRuntimeStartup").asBoolean()).isFalse();
            assertThat(manifestDefaults.path("includeHealthcheckGate").asBoolean()).isFalse();
            assertThat(manifestDefaults.path("contextValues").path("authorization").asText()).isEqualTo("[REDACTED]");
            Path sensitive = export(server, workspace, Map.of("projectName", PROJECT, "exportId", "sensitive", "mode", "sh",
                    "planName", "two", "includeResolvedSecrets", true, "when", "fixture-label", "includeRuntimeStartup", true));
            assertThat(Files.readString(sensitive.resolve("project.env"))).contains(secret, "SENSITIVE EXPORT");
            assertThat(packageText(sensitive)).contains("fixture-label");
            JsonNode manifest = JSON.readTree(sensitive.resolve("manifest.json").toFile());
            assertThat(manifest.path("workload")).hasSize(1);
            assertThat(manifest.path("workload").get(0).path("planName").asText()).isEqualTo("two");
            assertThat(manifest.path("includeRuntimeStartup").asBoolean()).isTrue();
            assertThat(ok(server, "execution_export.list", Map.of("projectName", PROJECT)).toString()).contains("safe", "sensitive");
            JsonNode automatic = execute(server, "execution_profile_export.export", Map.of("mode", "sh", "planName", "one"), true).path("result");
            assertThat(automatic.path("status").asText()).isEqualTo("ok");
            String id = automatic.path("details").path("exportId").asText();
            assertThat(id).isNotBlank().isNotIn("safe", "sensitive");
            assertThat(workspace.resolve(".mcpjvm/" + PROJECT + "/exports/" + id + "/manifest.json")).isRegularFile();
            target.assertNoRequest();
        }
    }
}
