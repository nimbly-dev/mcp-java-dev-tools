package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.DefaultExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the Java Tool output against the released TypeScript export contract. */
class ExecutionProfileExportMcpTypeScriptParityFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    private ExecutionProfileExportMcpTool tool;

    @BeforeEach
    void setUp() throws IOException {
        writeFixture();
        ArtifactJsonStore store = new ArtifactJsonStore(JSON);
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> Optional.of(workspace), store, new SqliteRunStateStore(JSON), JSON);
        ExecutionExportArtifactGateway gateway = new ExecutionExportArtifacts(support);
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(
                new ExecutionProfileExportOperationCatalog(new ExportExecutionProfileOperation(
                        gateway,
                        new ExecutionProfileExportArtifactInputMapper(JSON),
                        trace()),
                        ExecutionProfileExportMcpTool.operationExposure()));
        tool = new ExecutionProfileExportMcpTool(feature);
    }

    @Test
    void provesToolSuccessFailureAndReleasedSkillContractParity() throws Exception {
        assertReleasedTypeScriptEvidence();
        try (InputStream stream = getClass().getResourceAsStream(
                "/execution-profile-export/execution-profile-export-typescript-java-parity.json")) {
            assertThat(stream).isNotNull();
            for (JsonNode testCase : JSON.readTree(stream).path("cases")) {
                ExecutionProfileExportMcpRequest request = JSON.treeToValue(
                        testCase.path("input"), ExecutionProfileExportMcpRequest.class);
                assertFixture(testCase, tool.invokeMcpRequest(request));
            }
        }
    }

    private void assertReleasedTypeScriptEvidence() throws IOException {
        Path root = repositoryRoot();
        String action = Files.readString(root.resolve(
                "tools/features/execution-profile-export/actions/export_execution_profile.action.ts"));
        String input = Files.readString(root.resolve(
                "tools/contracts/tools-contracts/src/inputs/execution_profile_export.input.model.ts"));
        String regressionSkill = Files.readString(root.resolve(
                "skills/mcp-java-dev-tools-regression-export/SKILL.md"));
        String performanceSkill = Files.readString(root.resolve(
                "skills/mcp-java-dev-tools-performance-export/SKILL.md"));
        assertThat(action).contains("resultType: \"execution_profile_export\"", "exportId: out.exportId")
                .contains("performance_export_mode_unsupported", "execution_export_mode_required");
        assertThat(input).contains("exportId", "contextBindings", "contextValues")
                .contains("z.enum([\"ps1\", \"sh\", \"postman\"])");
        assertThat(regressionSkill).contains("execution_export", "export_id", "includeResolvedSecrets")
                .contains("Keep output stable for the same input.");
        assertThat(performanceSkill).contains("execution_export", "export_id", "performance_export_mode_unsupported")
                .contains("Keep output stable for the same input.");
    }

    private static void assertFixture(JsonNode testCase, McpActionResponse response) {
        JsonNode actual = JSON.valueToTree(response);
        Iterator<Entry<String, JsonNode>> assertions = testCase.path("assertions").fields();
        while (assertions.hasNext()) {
            Entry<String, JsonNode> assertion = assertions.next();
            assertThat(actual.at(assertion.getKey()).toString())
                    .as("%s %s", testCase.path("name").asText(), assertion.getKey())
                    .isEqualTo(assertion.getValue().toString());
        }
    }

    private void writeFixture() throws IOException {
        ArtifactJsonStore store = new ArtifactJsonStore(JSON);
        ObjectNode project = JSON.createObjectNode();
        project.putArray("workspaces").addObject()
                .put("projectRoot", workspace.toString())
                .putArray("executionProfiles").addObject()
                .put("executionProfile", "smoke")
                .put("executionPolicy", "stop_on_fail")
                .put("suiteType", "regression")
                .putArray("plans").addObject().put("order", 1).put("planName", "health");
        store.write(workspace.resolve(".mcpjvm/demo/projects.json"), project);
        ObjectNode contract = JSON.createObjectNode();
        contract.putArray("steps").addObject().put("order", 1).put("id", "health")
                .put("protocol", "http").putObject("transport").putObject("http")
                .put("method", "GET").put("url", "http://127.0.0.1:9196/health");
        store.write(workspace.resolve(".mcpjvm/demo/plans/regression/health/contract.json"), contract);
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

    private static OperationTraceMetadata trace() {
        return new OperationTraceMetadata(
                ExecutionProfileExportMcpTool.class.getName(),
                ExecutionProfileExportMcpRequestMapper.class.getName(),
                ExecutionProfileExportFeature.class.getName(),
                ExecutionProfileExportMcpResponseMapper.class.getName(),
                ExecutionProfileExportMcpTypeScriptParityFixtureTest.class.getName(),
                "filesystem_artifact_export",
                Map.of(
                        "artifactGateway", ExecutionExportArtifactGateway.class.getName(),
                        "artifactInputMapper", ExecutionProfileExportArtifactInputMapper.class.getName()));
    }
}
