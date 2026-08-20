package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compares Java's published branches against the released TypeScript contract source. */
class ArtifactManagementParityMatrixTest {

    private static final Pattern ACTION_GROUP = Pattern.compile("(?s)([a-z_]+): \\[([^]]*)\\]");
    private static final Pattern STRING = Pattern.compile("\\\"([^\\\"]+)\\\"");
    private static final Pattern FIELD = Pattern.compile("^ {2}([A-Za-z][A-Za-z0-9_]*)\\??\\s*:");

    @TempDir
    Path workspace;

    @Test
    void releasedTypeScriptActionsAndInputFieldsArePublishedByJava() throws IOException {
        Path root = repositoryRoot();
        String actionsSource = Files.readString(root.resolve(
                "tools/contracts/tools-contracts/src/inputs/artifact_management/shared/actions.model.ts"));
        Map<String, Set<String>> typescriptActions = parseActionAllowlist(actionsSource);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode javaSchema = mapper.readTree(ArtifactManagementMcpSchema.publicInputSchema(mapper));

        for (Map.Entry<String, Set<String>> family : typescriptActions.entrySet()) {
            Set<String> typescriptFields = typescriptFields(root, family.getKey());
            for (String actionValue : family.getValue()) {
                ArtifactManagementAction action = ArtifactManagementAction.resolve(
                        ArtifactType.fromValue(family.getKey()).orElseThrow(),
                        ArtifactAction.fromValue(actionValue).orElseThrow()).orElse(null);
                assertThat(action).as("Java action for TypeScript %s/%s", family.getKey(), actionValue)
                        .isNotNull();
                JsonNode branch = findBranch(javaSchema, family.getKey(), actionValue);
                assertThat(branch)
                        .as("Java schema branch for TypeScript %s/%s", family.getKey(), actionValue)
                        .isNotNull();
                assertThat(javaFieldsForBranch(branch))
                        .as("Java input fields for TypeScript %s/%s", family.getKey(), actionValue)
                        .containsAll(typescriptFields);
            }
        }
    }

    @Test
    void releasedOutputsDefaultsReasonsQueriesExportsAndSkillsHaveJavaEvidence() throws Exception {
        Path root = repositoryRoot();
        String failClosed = Files.readString(root.resolve(
                "tools/features/artifact-management/shared/fail_closed.ts"));
        String exportAction = Files.readString(root.resolve(
                "tools/features/execution-profile-export/actions/export_execution_profile.action.ts"));
        String runAction = Files.readString(root.resolve(
                "tools/features/artifact-management/actions/run_result.action.ts"));
        String defaults = Files.readString(root.resolve(
                "tools/features/execution-profile-export/policy/export_defaults.policy.ts"));
        String runInput = Files.readString(root.resolve(
                "tools/contracts/tools-contracts/src/inputs/artifact_management/run_result/input.model.ts"));
        String javaSources = javaSources(root);

        for (String field : List.of("resultType", "status", "reasonCode", "nextActionCode", "reason", "reasonMeta")) {
            assertThat(failClosed).as("TypeScript fail-closed output field %s", field).contains(field);
            assertThat(javaRecordFields(McpActionResponse.class)).as("Java output field %s", field).contains(field);
        }
        for (String field : List.of("mode", "suiteType", "exportId", "executionProfile", "exportDirAbs", "output")) {
            assertThat(exportAction).as("TypeScript export output field %s", field).contains(field);
            assertThat(javaSources).as("Java export output field %s", field).contains(field);
        }
        assertThat(exportAction).contains("status: \"ok\"", "resultType: \"execution_profile_export\"");
        assertThat(exportAction).contains("includeRuntimeStartup", "includeHealthcheckGate",
                "includeResolvedSecrets", "contextBindings", "contextValues", "when");
        assertThat(javaSources).contains("\"execution_profile_export\", \"ok\"");

        for (String field : List.of("includeRuntimeStartup", "includeHealthcheckGate", "includeResolvedSecrets")) {
            assertThat(defaults).as("TypeScript export default %s", field).contains(field);
        }
        assertThat(defaults).contains("let includeRuntimeStartup = true", "let includeHealthcheckGate = true")
                .contains("input.request.includeResolvedSecrets === true");
        assertThat(runInput).contains("pageSize: z.number().int().min(1).max(100).default(10)")
                .contains("sortDirection: z.enum([\"asc\", \"desc\"]).default(\"desc\")");
        assertThat(javaSources).contains("LIMIT ? OFFSET ?", "pageSize(query)", "asInt(10)",
                "asText(\"desc\")");

        List<String> parityReasons = List.of(
                "execution_export_mode_required", "execution_export_mode_conflict",
                "performance_export_mode_unsupported", "security_export_unsupported",
                "run_state_query_invalid", "state_store_rebuild_source_invalid",
                "legacy_backfill_source_invalid", "state_store_retention_invalid");
        for (String reasonCode : parityReasons) {
            assertThat(exportAction + runAction + runInput).as("TypeScript reason code %s", reasonCode)
                    .contains(reasonCode);
            assertThat(javaSources).as("Java reason code %s", reasonCode).contains(reasonCode);
        }

        assertThatQueryBehaviorIsBoundedAndFiltered();
        assertThatExportBehaviorMatchesReleasedDefaults();
        assertThatSkillWorkflowsConsumePublishedBranches(root);
    }

    private void assertThatQueryBehaviorIsBoundedAndFiltered() throws IOException {
        Path project = workspace.resolve(".mcpjvm/demo");
        Path runs = project.resolve("plans/regression/plan/runs");
        for (int index = 0; index <= 1000; index++) {
            Path run = runs.resolve("run-" + index);
            Files.createDirectories(run);
            Files.writeString(run.resolve("execution.result.json"),
                    "{\"status\":\"pass\",\"executionProfile\":\""
                            + (index == 1000 ? "fast" : "slow") + "\",\"startedAt\":" + index + "}");
        }
        SqliteRunStateStore store = new SqliteRunStateStore(new ObjectMapper());
        Path database = project.resolve("run-state.sqlite");
        store.rebuild(database, "demo");
        ObjectNode query = new ObjectMapper().createObjectNode()
                .put("executionProfile", "fast").put("pageSize", 1);
        Map<String, Object> result = store.query(database, "demo", "run_state", query);
        assertThat(result.get("items").toString()).contains("run-1000").doesNotContain("run-999");
        assertThat(result.get("pageSize")).isEqualTo(1);
    }

    private void assertThatExportBehaviorMatchesReleasedDefaults() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode project = mapper.createObjectNode();
        project.putArray("workspaces").addObject()
                .put("projectRoot", workspace.toString())
                .putArray("executionProfiles").addObject()
                .put("executionProfile", "smoke").put("executionPolicy", "stop_on_fail")
                .put("suiteType", "regression").putArray("plans").addObject()
                .put("order", 1).put("planName", "health");
        ArtifactJsonStore store = new ArtifactJsonStore(mapper);
        Path projectPath = workspace.resolve(".mcpjvm/demo/projects.json");
        store.write(projectPath, project);
        Path contract = workspace.resolve(".mcpjvm/demo/plans/regression/health/contract.json");
        ObjectNode contractValue = mapper.createObjectNode();
        contractValue.putArray("steps").addObject().put("id", "health").put("protocol", "http")
                .putObject("transport").putObject("http").put("method", "GET")
                .put("url", "http://127.0.0.1:9196/health");
        store.write(contract, contractValue);
        ArtifactWorkspaceProvider provider = () -> Optional.of(workspace);
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                provider, store, new SqliteRunStateStore(mapper), mapper);
        ArtifactManagementRequest request = new ArtifactManagementRequest(
                ArtifactType.EXECUTION_EXPORT,
                ArtifactAction.GENERATE,
                mapper.createObjectNode().put("projectName", "demo").put("mode", "sh")
                        .put("executionProfile", "smoke"));
        ArtifactManagementResult result = new ExecutionExportArtifacts(support).generate(request);
        assertThat(result.resultType()).isEqualTo("execution_profile_export");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.details()).containsKeys("exportDirAbs", "output", "suiteType", "executionProfile");
        Path exportDir = Path.of(String.valueOf(result.details().get("exportDirAbs")));
        JsonNode manifest = mapper.readTree(Files.readString(exportDir.resolve("manifest.json")));
        assertThat(manifest.path("includeRuntimeStartup").asBoolean()).isTrue();
        assertThat(manifest.path("includeHealthcheckGate").asBoolean()).isTrue();
        assertThat(manifest.path("includeResolvedSecrets").asBoolean()).isFalse();
        assertThat(Files.isRegularFile(exportDir.resolve("run-execution-profile.sh"))).isTrue();
    }

    private static void assertThatSkillWorkflowsConsumePublishedBranches(Path root) throws IOException {
        List<Path> workflowDocs = List.of(
                root.resolve("skills/mcp-java-dev-tools-regression-export/SKILL.md"),
                root.resolve("skills/mcp-java-dev-tools-performance-export/SKILL.md"),
                root.resolve("skills/mcp-java-dev-tools-regression-suite-diagnostic/references/mcp-query-playbook.md"),
                root.resolve("skills/mcp-java-dev-tools-security-suite-diagnostic/references/mcp-query-playbook.md"));
        for (Path workflow : workflowDocs) {
            String source = Files.readString(workflow);
            assertThat(source).as("Skill Workflow %s", workflow).contains("artifactType");
            if (workflow.toString().contains("export")) {
                assertThat(source).contains("execution_export", "includeRuntimeStartup",
                        "includeHealthcheckGate", "includeResolvedSecrets", "scriptRefs", "envFileArg");
            } else {
                assertThat(source).contains("run_result", "action", "query", "pageSize", "stateSurface");
            }
        }
    }

    private static Set<String> javaRecordFields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(component -> component.getName()).collect(java.util.stream.Collectors.toSet());
    }

    private static String javaSources(Path root) throws IOException {
        Path sourceRoot = root.resolve("mcp-server/core/src/main/java");
        Path applicationRoot = root.resolve("mcp-server/application/src/main/java");
        StringBuilder source = new StringBuilder();
        for (Path base : List.of(sourceRoot, applicationRoot)) {
            try (var files = Files.walk(base)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            try {
                                source.append(Files.readString(path));
                            } catch (IOException exception) {
                                throw new java.io.UncheckedIOException(exception);
                            }
                        });
            }
        }
        return source.toString();
    }

    private static Map<String, Set<String>> parseActionAllowlist(String source) {
        int start = source.indexOf("ARTIFACT_ACTION_ALLOWLIST");
        int end = source.indexOf("} as const", start);
        String section = source.substring(start, end);
        Matcher groups = ACTION_GROUP.matcher(section);
        Map<String, Set<String>> result = new LinkedHashMap<>();
        while (groups.find()) {
            Set<String> values = new HashSet<>();
            Matcher strings = STRING.matcher(groups.group(2));
            while (strings.find()) {
                values.add(strings.group(1));
            }
            result.put(groups.group(1), values);
        }
        return result;
    }

    private static Set<String> typescriptFields(Path root, String family) throws IOException {
        Path familyPath = root.resolve("tools/contracts/tools-contracts/src/inputs/artifact_management")
                .resolve(family).resolve("input.model.ts");
        Set<String> fields = new HashSet<>();
        String familySource = Files.readString(familyPath);
        collectInputFields(familySource, fields);
        if (familySource.contains("ProjectScopedInputSchema")) {
            fields.add("projectName");
        }
        return fields;
    }

    private static void collectInputFields(String source, Set<String> fields) {
        int declaration = source.indexOf("InputSchema =");
        int objectStart = source.indexOf('{', declaration);
        if (declaration < 0 || objectStart < 0) {
            throw new IllegalStateException("TypeScript input schema object could not be located");
        }
        int depth = 0;
        for (String line : source.substring(objectStart).split("\\R")) {
            if (depth == 1) {
                Matcher field = FIELD.matcher(line);
                if (field.find()) {
                    fields.add(field.group(1));
                }
            }
            depth += count(line, '{') - count(line, '}');
            if (depth <= 0) {
                break;
            }
        }
    }

    private static int count(String source, char value) {
        int count = 0;
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) == value) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> javaFieldsForBranch(JsonNode branch) {
        Set<String> fields = new HashSet<>();
        branch.path("properties").path("input").path("properties").fieldNames()
                .forEachRemaining(fields::add);
        return fields;
    }

    private static JsonNode findBranch(JsonNode schema, String family, String action) {
        for (JsonNode branch : schema.path("oneOf")) {
            JsonNode properties = branch.path("properties");
            if (family.equals(properties.path("artifactType").path("const").asText())
                    && action.equals(properties.path("action").path("const").asText())) {
                return branch;
            }
        }
        return null;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("tools/contracts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root could not be located");
        }
        return current;
    }
}
