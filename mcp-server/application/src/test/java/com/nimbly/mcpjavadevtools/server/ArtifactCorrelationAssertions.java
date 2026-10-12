package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Storage acceptance consumes real correlation Artifacts from the existing event scenario. */
class ArtifactCorrelationAssertions extends ArtifactMcpSupport {
    static void verify(McpServerProcess server, Path source, String project, String plan, String run) throws Exception {
        JsonNode produced = JSON.readTree(source.toFile());
        byte[] original = Files.readAllBytes(source);
        JsonNode read = ok(server, "run_result.read", Map.of("projectName", project, "planName", plan, "runId", run));
        assertThat(read.path("correlation")).isEqualTo(produced);
        ok(server, "run_result.rebuild", Map.of("projectName", project, "strict", true));
        JsonNode projected = ok(server, "run_result.query", Map.of("projectName", project, "stateSurface", "correlation_state",
                "query", Map.of("planName", plan, "runId", run))).path("query").path("items");
        assertThat(projected).hasSize(1);
        assertThat(projected.get(0).path("state")).isEqualTo(produced);
        for (int attempt = 0; attempt < 2; attempt++) {
            JsonNode backfill = ok(server, "run_result.backfill", Map.of("projectName", project, "stateSurface", "correlation_state"));
            if (attempt == 1) {
                assertThat(backfill.path("backfill").path("imported").asInt()).isZero();
            }
        }
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
        JsonNode after = ok(server, "run_result.query", Map.of("projectName", project, "stateSurface", "correlation_state",
                "query", Map.of("planName", plan, "runId", run))).path("query").path("items");
        assertThat(after.get(0).path("state")).isEqualTo(produced);
    }
}
