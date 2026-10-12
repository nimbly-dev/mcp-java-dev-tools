package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("artifact")
@Timeout(120)
class McpServerArtifactSafetyIT extends ArtifactMcpSupport {
    @Test
    void retiredResultFilenameCannotBecomeAuthoritativeRunState() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            Path directory = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/current");
            Files.createDirectories(directory);
            Path retired = directory.resolve("result.json");
            Files.writeString(retired, "{\"status\":\"blocked\",\"reasonCode\":\"retired_storage_input\"}");
            assertThat(ok(server, "run_result.read", runInput("current")).has("artifact")).isFalse();
            blocked(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true), "state_store_rebuild_strict_failed");
            JsonNode rebuilt = ok(server, "run_result.rebuild", Map.of("projectName", PROJECT)).path("rebuild");
            assertThat(rebuilt.path("replayedRuns").asInt()).isZero();
            assertThat(rebuilt.path("summary").path("invalidRuns").asInt()).isEqualTo(1);
            assertThat(query(server, "run_state", Map.of()).path("items")).isEmpty();
            ok(server, "run_result.upsert", Map.of("projectName", PROJECT, "planName", "plan", "runId", "current",
                    "payload", Map.of("status", "blocked", "reasonCode", "current_storage_input")));
            assertThat(ok(server, "run_result.read", runInput("current")).path("artifact").path("reasonCode").asText())
                    .isEqualTo("current_storage_input");
            assertThat(ok(server, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true))
                    .path("rebuild").path("replayedRuns").asInt()).isEqualTo(1);
            assertThat(query(server, "run_state", Map.of()).path("items")).hasSize(1);
            assertThat(Files.readString(retired)).contains("retired_storage_input");
        }
    }

    @Test
    void linkedArtifactDirectoryCannotReadOrWriteOutsideTheBoundWorkspace() throws Exception {
        Path workspace = JavaItScenario.workspace();
        Path outside = JavaItScenario.workspace();
        Path link = workspace.resolve(".mcpjvm");
        Files.writeString(outside.resolve("sentinel"), "unrelated owned state");
        createDirectoryLink(link, outside);
        try (McpServerProcess server = start(workspace)) {
            blocked(server, "project_context.upsert", Map.of("projectName", PROJECT, "payload", project(workspace)), "artifact_path_symlink_escape");
            blocked(server, "probe_config.upsert", Map.of("payload", McpServerArtifactConfigIT.registry(workspace)), "artifact_path_symlink_escape");
            blocked(server, "project_context.list", Map.of(), "artifact_path_symlink_escape");
            blocked(server, "run_result.rebuild", Map.of("projectName", PROJECT), "artifact_path_symlink_escape");
            try (var files = Files.list(outside)) {
                assertThat(files.map(path -> path.getFileName().toString())).containsExactly("sentinel");
            }
            assertThat(Files.readString(outside.resolve("sentinel"))).isEqualTo("unrelated owned state");
        } finally {
            Files.delete(link);
        }
    }

    static void createDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | IOException failure) {
            assertThat(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT))
                    .as("Directory link capability is a required safety-test prerequisite").contains("win");
        }
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        JavaItScenario.own(process);
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("Owned junction creation completes").isTrue();
        assertThat(process.exitValue()).as("Directory junction prerequisite").isZero();
    }

    @Test
    void invalidRegistryReadFallsBackOnlyToAcceptedSnapshotAndRecovers() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            ObjectNode config = McpServerArtifactConfigIT.registry(workspace);
            ok(server, "probe_config.upsert", Map.of("payload", config));
            Path source = workspace.resolve(".mcpjvm/probe-config.json");
            Files.writeString(source, "{controlled interrupted configuration");
            JsonNode snapshot = ok(server, "probe_config.read", Map.of());
            assertThat(snapshot.path("probeCount").asInt()).isEqualTo(2);
            assertThat(snapshot.has("artifact")).isFalse();
            for (String action : List.of("validate", "reload")) {
                blocked(server, "probe_config." + action, Map.of(), "artifact_json_invalid");
            }
            ok(server, "probe_config.upsert", Map.of("payload", config));
            assertThat(ok(server, "probe_config.read", Map.of()).path("artifact")).isEqualTo(config);
            assertThat(artifact(server, "probe_config.reload", Map.of()).path("status").asText()).isEqualTo("reloaded");
        }
    }

    @Test
    void futureSqliteSchemaIsRejectedWithoutChangingAuthoritativeState() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            Path project = workspace.resolve(".mcpjvm/" + PROJECT);
            byte[] original = Files.readAllBytes(project.resolve("projects.json"));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.resolve("run-state.sqlite"));
                    var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO schema_migrations(version) VALUES(999)");
            }
            for (String action : List.of("query", "rebuild", "cutover", "cleanup")) {
                blocked(server, "run_result." + action, Map.of("projectName", PROJECT), "state_store_schema_unsupported");
            }
            blocked(server, "run_result.backfill", Map.of("projectName", PROJECT, "stateSurface", "correlation_state"),
                    "state_store_schema_unsupported");
            assertThat(Files.readAllBytes(project.resolve("projects.json"))).isEqualTo(original);
            assertThat(project.resolve("state-store.cutover.json")).doesNotExist();
        }
    }
}
