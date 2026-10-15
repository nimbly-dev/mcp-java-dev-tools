package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.nio.file.ExtendedOpenOption;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Failed publication and mixed run envelopes preserve coherent evidence through the public MCP boundary. */
@Tag("artifact")
@Tag("skill-workflows")
@Timeout(120)
class McpServerRunArtifactRecoveryIT extends ArtifactMcpSupport {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void failedCanonicalMoveRollsBackCompanionsBeforeReadAndRetry() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ObjectNode payload = JSON.createObjectNode().put("status", "pass");
            payload.putObject("details").put("summary", "original");
            payload.putObject("correlation").put("correlationSessionId", "original");
            Map<String, Object> input = Map.of("projectName", PROJECT, "planName", "plan", "runId", "run", "payload", payload);
            ok(server, "run_result.upsert", input);
            Path run = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/run");
            Map<String, byte[]> before = snapshot(run);
            payload.put("status", "fail");
            payload.withObject("details").put("summary", "replacement");
            payload.withObject("correlation").put("correlationSessionId", "replacement");
            try (var held = FileChannel.open(run.resolve("execution.result.json"),
                    Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE))) {
                assertThat(held.isOpen()).isTrue();
                blocked(server, "run_result.upsert", input, "artifact_write_failed");
                for (var file : before.entrySet()) {
                    assertThat(Files.readAllBytes(run.resolve(file.getKey()))).as(file.getKey()).isEqualTo(file.getValue());
                }
                JsonNode read = ok(server, "run_result.read", runInput("run"));
                assertThat(read.path("artifact").path("status").asText()).isEqualTo("pass");
                assertThat(read.path("evidence").path("status").asText()).isEqualTo("pass");
            }
            ok(server, "run_result.upsert", input);
            JsonNode read = ok(server, "run_result.read", runInput("run"));
            assertThat(read.path("artifact").path("status").asText()).isEqualTo("fail");
            assertThat(read.path("evidence").path("status").asText()).isEqualTo("fail");
        }
        JavaItScenario.observe("artifact_management.run_result.upsert", "ASSERTED", "real_last_move_failure_rollback_read_retry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"nested", "root", "duplicate", "direct"})
    void largeMixedShapeEvidenceRemainsReadableAndProjectsOnceAfterRestart(String shape) throws Exception {
        Path workspace = JavaItScenario.workspace();
        ObjectNode payload = payload(shape);
        Path canonical = workspace.resolve(".mcpjvm/" + PROJECT + "/plans/regression/plan/runs/run/execution.result.json");
        try (McpServerProcess server = start(workspace)) {
            createProject(server, workspace);
            ok(server, "run_result.upsert", Map.of("projectName", PROJECT, "planName", "plan", "runId", "run", "payload", payload));
            assertEvidence(server, payload);
        }
        byte[] original = Files.readAllBytes(canonical);
        try (McpServerProcess restarted = start(workspace)) {
            assertEvidence(restarted, payload);
            ok(restarted, "run_result.rebuild", Map.of("projectName", PROJECT, "strict", true));
            JsonNode watchers = payload.path("watchers").isArray() ? payload.path("watchers") : payload.path("details").path("watchers");
            assertThat(query(restarted, "watcher_state", Map.of("runId", "run")).path("items").get(0).path("state"))
                    .isEqualTo(watchers);
            assertThat(query(restarted, "run_state", Map.of()).path("items")).hasSize(1);
            assertThat(Files.readAllBytes(canonical)).isEqualTo(original);
        }
        JavaItScenario.observe("artifact_management.run_result.rebuild", "ASSERTED", "large_evidence_once_" + shape);
    }

    private ObjectNode payload(String shape) {
        ObjectNode payload = JSON.createObjectNode().put("status", "blocked");
        ObjectNode details = payload.putObject("details").put("summary", "retained metadata");
        var watchers = details.putArray("watchers");
        for (int index = 0; index < 14; index++) {
            watchers.addObject().put("id", "watcher-" + index).put("status", "blocked").put("evidence", "x".repeat(2800));
        }
        if (!shape.equals("nested")) {
            payload.set("watchers", watchers.deepCopy());
        }
        if (shape.equals("root")) {
            details.remove("watchers");
        } else if (shape.equals("direct")) {
            payload.putObject("directResult").set("details", details.deepCopy());
        }
        payload.putArray("steps").addObject().put("id", "root-step").put("status", "blocked");
        return payload;
    }

    private void assertEvidence(McpServerProcess server, ObjectNode expected) throws Exception {
        JsonNode read = ok(server, "run_result.read", runInput("run"));
        assertThat(read.path("artifact")).isEqualTo(expected);
        assertThat(read.path("evidence").path("steps")).isEqualTo(expected.path("steps"));
        assertThat(read.path("evidence").path("summary").asText()).isEqualTo("retained metadata");
    }

    private Map<String, byte[]> snapshot(Path directory) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : List.of("correlation.json", "context.resolved.json", "evidence.json", "execution.result.json")) {
            result.put(name, Files.readAllBytes(directory.resolve(name)));
        }
        return result;
    }
}
