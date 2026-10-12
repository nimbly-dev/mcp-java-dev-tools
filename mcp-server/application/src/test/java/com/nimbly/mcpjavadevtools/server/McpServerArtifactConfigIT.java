package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("artifact")
@Timeout(120)
class McpServerArtifactConfigIT extends ArtifactMcpSupport {
    @Test
    void catalogAndDescribeExposeExactlyTheArtifactActionAllowlists() throws Exception {
        try (McpServerProcess server = start(JavaItScenario.workspace())) {
            List<String> expected = new ArrayList<>();
            ACTIONS.forEach((family, actions) -> actions.forEach(action -> expected.add("artifact_management." + family + "." + action)));
            server.send(request(20, "tools/call", Map.of("name", "operation_catalog",
                    "arguments", Map.of("api", "artifact_management", "limit", 50))));
            assertThat(toolPayload(server.responseFor(20)).path("entries"))
                    .extracting(entry -> entry.path("operationId").asText()).containsExactlyInAnyOrderElementsOf(expected);
            for (String operation : expected) {
                server.send(request(21, "tools/call", Map.of("name", "operation_describe", "arguments", Map.of("operationId", operation))));
                JsonNode description = toolPayload(server.responseFor(21)).path("operation");
                assertThat(description.path("inputSchema").path("additionalProperties").asBoolean(true)).isFalse();
                JsonNode cancellation = description.path("safety").path("cancellationSupported");
                assertThat(cancellation.isBoolean()).isTrue();
                assertThat(cancellation.asBoolean()).isFalse();
                Map<String, Object> invalid = Map.of("unrestrictedFile", "outside-secret-file");
                assertThat(execute(server, operation, invalid, true).path("status").asText()).isEqualTo("invalid_input");
            }
            for (String family : ACTIONS.keySet()) {
                String operation = "artifact_management." + family + ".delete";
                assertThat(execute(server, operation, Map.of(), true).path("status").asText()).isEqualTo("unsupported_operation");
            }
            assertThat(execute(server, "artifact_management.run_result.validate", Map.of(), true)
                    .path("status").asText()).isEqualTo("unsupported_operation");
        }
    }

    @Test
    @Tag("artifact-smoke")
    void registryRoundTripReloadInvalidInputAndRestart() throws Exception {
        Path workspace = JavaItScenario.workspace();
        ObjectNode config = registry(workspace);
        try (McpServerProcess server = start(workspace)) {
            for (String action : List.of("read", "validate", "reload")) {
                JsonNode missing = artifact(server, "probe_config." + action, Map.of());
                assertThat(missing.path("reasonCode").asText()).isEqualTo("probe_registry_not_configured");
            }
            assertThat(execute(server, "artifact_management.probe_config.upsert", Map.of("payload", config), false)
                    .path("status").asText()).isEqualTo("confirmation_required");
            assertThat(workspace.resolve(".mcpjvm/probe-config.json")).doesNotExist();
            JsonNode upsert = ok(server, "probe_config.upsert", Map.of("payload", config));
            assertThat(upsert.path("reloadApplied").asBoolean()).isTrue();
            assertThat(upsert.path("activeProbeCount").asInt()).isEqualTo(2);
            assertThat(JSON.readTree(workspace.resolve(".mcpjvm/probe-config.json").toFile())).isEqualTo(config);
            ok(server, "probe_config.validate", Map.of());
            assertThat(ok(server, "probe_config.read", Map.of()).path("artifact")).isEqualTo(config);
            JsonNode reload = artifact(server, "probe_config.reload", Map.of());
            assertThat(reload.path("status").asText()).isEqualTo("reloaded");
            assertThat(reload.path("details").path("activeProbeCount").asInt()).isEqualTo(2);
            ObjectNode invalid = config.deepCopy();
            invalid.with("profiles").with("local").put("defaultProbe", "one");
            blocked(server, "probe_config.upsert", Map.of("payload", invalid), "probe_config_invalid");
            assertThat(JSON.readTree(workspace.resolve(".mcpjvm/probe-config.json").toFile())).isEqualTo(config);
        }
        try (McpServerProcess restarted = start(workspace)) {
            assertThat(ok(restarted, "probe_config.read", Map.of()).path("artifact")).isEqualTo(config);
            assertThat(artifact(restarted, "probe_config.reload", Map.of()).path("status").asText()).isEqualTo("reloaded");
        }
    }

    static ObjectNode registry(Path workspace) {
        ObjectNode config = JSON.createObjectNode().put("defaultProfile", "local");
        ObjectNode probes = config.putObject("profiles").putObject("local").putObject("probes");
        for (String id : List.of("one", "two")) {
            ObjectNode probe = probes.putObject(id).put("baseUrl", "http://127.0.0.1:1");
            probe.putArray("include").add("example.**");
            probe.putArray("exclude");
        }
        config.putArray("workspaces").addObject().put("root", workspace.toString()).put("profile", "local");
        return config;
    }

    @Test
    void projectMergeReplaceSelectorsAndRestartPreserveConfiguration() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path other = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            blocked(server, "project_context.read", Map.of(), "project_artifact_missing");
            assertThat(ok(server, "project_context.list", Map.of()).path("projectNames")).isEmpty();
            createProject(server, workspace);
            ObjectNode patch = project(workspace);
            ObjectNode context = (ObjectNode) patch.path("workspaces").get(0);
            context.remove(List.of("executionProfiles", "variables"));
            context.putObject("sessionExport").put("includeRuntimeStartup", false);
            assertThat(ok(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", patch))
                    .path("updateMode").asText()).isEqualTo("merged");
            JsonNode persisted = JSON.readTree(workspace.resolve(".mcpjvm/" + PROJECT + "/projects.json").toFile());
            assertThat(persisted.path("workspaces").path(0).path("executionProfiles")).hasSize(3);
            assertThat(persisted.path("workspaces").path(0).path("variables").path("contextBindings").path("auth.bearer").asText())
                    .isEqualTo("AUTH_TOKEN");
            assertThat(ok(server, "project_context.validate", Map.of("projectRootAbs", workspace.toString())).path("valid").asBoolean()).isTrue();
            JsonNode read = ok(server, "project_context.read", Map.of("query", Map.of("select", List.of("executionProfiles"),
                    "executionProfile", "performance")));
            assertThat(read.path("executionProfiles")).hasSize(1);
            blocked(server, "project_context.read", Map.of("projectName", PROJECT, "projectRootAbs", other.toString()), "project_scope_mismatch");
            ok(server, "project_context.upsert", Map.of("projectName", "second", "payload", project(workspace)));
            blocked(server, "project_context.read", Map.of(), "project_artifact_ambiguous");
            blocked(server, "project_context.read", Map.of("projectRootAbs", workspace.toString()), "project_artifact_ambiguous");
            assertThat(ok(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", patch, "replace", true))
                    .path("updateMode").asText()).isEqualTo("replaced");
        }
        try (McpServerProcess restarted = start(workspace)) {
            JsonNode read = ok(restarted, "project_context.read", Map.of("projectName", PROJECT, "query", Map.of("select", List.of("artifact"))));
            assertThat(read.path("artifact").path("workspaces").path(0).has("executionProfiles")).isFalse();
            assertThat(ok(restarted, "project_context.list", Map.of()).path("projectNames"))
                    .extracting(JsonNode::asText).containsExactly(PROJECT, "second");
        }
    }

    @Test
    void invalidProjectsTraversalAndFailedProvisioningNeverOverwriteAuthoritativeData() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            blocked(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", Map.of()), "project_artifact_invalid");
            assertThat(workspace.resolve(".mcpjvm/" + PROJECT)).doesNotExist();
            createProject(server, workspace);
            Path projectFile = workspace.resolve(".mcpjvm/" + PROJECT + "/projects.json");
            byte[] before = Files.readAllBytes(projectFile);
            blocked(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", Map.of("workspaces", List.of())),
                    "project_artifact_invalid");
            for (String name : List.of("..", "../outside", "a/b", "a\\b", workspace.toString())) {
                blocked(server, "project_context.upsert", Map.of("projectName", name, "payload", project(workspace)), "artifact_path_segment_invalid");
            }
            assertThat(Files.readAllBytes(projectFile)).isEqualTo(before);
            Path failed = workspace.resolve(".mcpjvm/failed/projects.json");
            Files.createDirectories(failed);
            Files.writeString(failed.resolve("keep"), "owned obstruction");
            blocked(server, "project_context.upsert", Map.of("projectName", "failed", "payload", project(workspace)), "project_artifact_write_failed");
            assertThat(failed.resolveSibling("run-state.sqlite")).doesNotExist();
            assertThat(Files.readString(failed.resolve("keep"))).isEqualTo("owned obstruction");
            assertThat(Files.readAllBytes(projectFile)).isEqualTo(before);
            assertNoTemporaryFiles(workspace);
        }
    }

    static void assertNoTemporaryFiles(Path workspace) throws Exception {
        try (var files = Files.walk(workspace)) {
            assertThat(files.filter(path -> path.getFileName().toString().endsWith(".tmp"))).isEmpty();
        }
    }
}
