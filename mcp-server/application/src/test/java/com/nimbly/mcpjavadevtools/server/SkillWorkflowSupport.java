package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Inventory assertions and bounded execution of existing repository Skill helpers. */
class SkillWorkflowSupport extends McpServerStdioSupport {
    static JsonNode helper(Path workspace, String name, Map<String, Object> input) throws Exception {
        Path script = repositoryRoot().resolve("skills/mcp-java-dev-tools-regression-suite/scripts/" + name + ".js");
        assertThat(script).isRegularFile();
        Process process = new ProcessBuilder(System.getProperty("mcpItNode", "node"), script.toString())
                .directory(workspace.toFile()).redirectError(workspace.resolve("helper-error.log").toFile()).start();
        JavaItScenario.own(process);
        try {
            process.getOutputStream().write(JSON.writeValueAsBytes(input));
            process.getOutputStream().close();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("bounded Skill helper").isTrue();
            assertThat(process.exitValue()).as(name).isZero();
            byte[] output = process.getInputStream().readNBytes(16_385);
            assertThat(output.length).isLessThanOrEqualTo(16_384);
            return JSON.readTree(new String(output, StandardCharsets.UTF_8));
        } finally {
            JavaItScenario.stopTree(process.toHandle());
        }
    }

    static void assertInventory(McpServerProcess server, Path workspace) throws Exception {
        Path file = repositoryRoot().resolve("docs/acceptance/java-it-coverage.json");
        JsonNode inventory = JSON.readTree(file.toFile());
        Set<String> expected = new HashSet<>();
        for (JsonNode row : inventory.path("operations")) {
            assertThat(expected.add(row.path("operationId").asText())).as("unique inventory operation").isTrue();
            JsonNode group = inventory.path("scenarioGroups").path(row.path("scenarioGroup").asText());
            assertThat(group.path("scenarios")).isNotEmpty();
            assertThat(group.path("owner").asInt()).isBetween(647, 655);
        }
        List<String> actual = catalog(server);
        assertThat(actual).as("Added, removed or unmapped registrations require a coverage disposition")
                .containsExactlyInAnyOrderElementsOf(expected);
        for (String operation : actual) {
            server.send(request(500, "tools/call", Map.of("name", "operation_describe", "arguments", Map.of("operationId", operation))));
            JsonNode described = toolPayload(server.responseFor(500));
            assertThat(described.path("status").asText()).isEqualTo("succeeded");
            assertThat(described.path("operation").path("operationId").asText()).isEqualTo(operation);
            assertThat(described.path("operation").path("inputSchema").isObject()).isTrue();
        }
        assertSkills(inventory);
        JsonNode unknown = ArtifactMcpSupport.execute(server, "missing.operation", Map.of(), true);
        assertThat(unknown.path("status").asText()).isEqualTo("unsupported_operation");
        Files.copy(file, workspace.resolve("coverage-input.json"));
        JavaItScenario.observe("operation_catalog", "ASSERTED", "all_operations_mapped_describable_skill_dependencies_present");
    }

    private static List<String> catalog(McpServerProcess server) throws Exception {
        List<String> operations = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Map<String, Object> arguments = Map.of("limit", 50);
        for (int page = 0; page < 20; page++) {
            server.send(request(501, "tools/call", Map.of("name", "operation_catalog", "arguments", arguments)));
            JsonNode result = toolPayload(server.responseFor(501));
            assertThat(result.path("status").asText()).isEqualTo("succeeded");
            result.path("entries").forEach(entry -> operations.add(entry.path("operationId").asText()));
            String cursor = result.path("nextCursor").asText("");
            if (cursor.isEmpty()) {
                assertThat(operations).doesNotHaveDuplicates();
                return operations;
            }
            assertThat(cursors.add(cursor)).as("catalog pagination advances").isTrue();
            arguments = Map.of("limit", 50, "cursor", cursor);
        }
        throw new AssertionError("Catalog pagination exceeded bounded inventory read");
    }

    private static void assertSkills(JsonNode inventory) throws Exception {
        Set<String> mapped = new HashSet<>();
        for (JsonNode skill : inventory.path("skills")) {
            mapped.add(skill.path("path").asText());
            assertThat(skill.path("sequence")).isNotEmpty();
            for (JsonNode dependency : skill.path("dependencies")) {
                assertThat(repositoryRoot().resolve(dependency.asText())).isRegularFile();
            }
        }
        try (var files = Files.walk(repositoryRoot().resolve("skills"))) {
            List<String> actual = files.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .map(path -> repositoryRoot().relativize(path).toString().replace('\\', '/')).toList();
            assertThat(actual).containsExactlyInAnyOrderElementsOf(mapped);
        }
    }
}
