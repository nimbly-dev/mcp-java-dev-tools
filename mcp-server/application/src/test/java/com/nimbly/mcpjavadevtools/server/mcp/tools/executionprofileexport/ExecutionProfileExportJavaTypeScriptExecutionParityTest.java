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
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the released TypeScript exporter and compares its artifacts with Java output. */
class ExecutionProfileExportJavaTypeScriptExecutionParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void comparesExecutedJavaAndTypeScriptPerformanceArtifacts() throws Exception {
        Path typescriptRoot = workspace.resolve("typescript");
        Path javaRoot = workspace.resolve("java");
        writeFixture(typescriptRoot);
        writeFixture(javaRoot);
        JsonNode typescript = runTypeScript(typescriptRoot);
        Path javaExport = generateJava(javaRoot);

        assertThat(typescript.path("status").asText()).as(typescript.toPrettyString()).isEqualTo("ok");
        assertThat(typescript.path("files").fieldNames()).toIterable()
                .contains("performance-export.bundle.json", "run-performance-profile.js",
                        "run-performance-profile.ps1", "README.performance.ps1.md",
                        "artifacts/jmeter/load.workload.jmeter.jmx");
        assertThat(Files.isRegularFile(javaExport.resolve("performance-export.bundle.json"))).isTrue();
        assertThat(Files.isRegularFile(javaExport.resolve("artifacts/jmeter/load.workload.jmeter.jmx"))).isTrue();
        compareBundles(JSON.readTree(typescript.path("files").path("performance-export.bundle.json").asText()),
                JSON.readTree(Files.readString(javaExport.resolve("performance-export.bundle.json"))));
        compareJmeter(typescript.path("files").path("artifacts/jmeter/load.workload.jmeter.jmx"),
                Files.readString(javaExport.resolve("artifacts/jmeter/load.workload.jmeter.jmx")));
        compareDocumentation(typescript.path("files").path("README.performance.ps1.md").asText(),
                Files.readString(javaExport.resolve("README.performance.ps1.md")));
    }

    private JsonNode runTypeScript(Path root) throws Exception {
        Path repository = repositoryRoot();
        Process process = new ProcessBuilder(
                "node", "--require", "ts-node/register", "--require", "tsconfig-paths/register",
                repository.resolve("test/fixtures/execution-profile-export/typescript-output-runner.cjs").toString(),
                root.toString())
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        String[] lines = output.trim().split("\\R");
        return JSON.readTree(lines[lines.length - 1]);
    }

    private Path generateJava(Path root) throws IOException {
        ArtifactJsonStore store = new ArtifactJsonStore(JSON);
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> Optional.of(root), store, new SqliteRunStateStore(JSON), JSON);
        ExecutionExportArtifactGateway gateway = new ExecutionExportArtifacts(support);
        ArtifactManagementRequest request = new ArtifactManagementRequest(
                ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE,
                JSON.createObjectNode().put("projectName", "demo").put("executionProfile", "load-suite")
                        .put("mode", "ps1").put("exportId", "20260101-000000-load-suite"));
        ArtifactManagementResult result = gateway.generate(request);
        assertThat(result.status()).isEqualTo("ok");
        return Path.of(String.valueOf(result.details().get("exportDirAbs")));
    }

    private void compareBundles(JsonNode typescript, JsonNode java) {
        assertThat(java.path("exportId").asText()).isEqualTo(typescript.path("exportId").asText());
        assertThat(java.path("suiteType").asText()).isEqualTo(typescript.path("suiteType").asText());
        assertThat(java.path("executionProfile").asText()).isEqualTo(typescript.path("executionProfile").asText());
        assertThat(java.path("plans").path(0).path("contract").path("loadModel"))
                .isEqualTo(typescript.path("plans").path(0).path("contract").path("loadModel"));
        assertThat(java.path("plans").path(0).path("planName").asText())
                .isEqualTo(typescript.path("plans").path(0).path("planName").asText());
    }

    private void compareJmeter(JsonNode typescript, String java) {
        assertThat(java).contains("<jmeterTestPlan", "GET http://127.0.0.1:8080/health",
                "<stringProp name=\"ThreadGroup.num_threads\">10</stringProp>");
        assertThat(typescript.asText()).contains("<jmeterTestPlan", "GET http://127.0.0.1:8080/health",
                "<stringProp name=\"ThreadGroup.num_threads\">10</stringProp>");
    }

    private void compareDocumentation(String typescript, String java) {
        for (String line : new String[] {"SuiteType: `performance`", "ExecutionProfile: `load-suite`",
                "## JMeter Artifacts", "artifacts/jmeter/load.workload.jmeter.jmx"}) {
            assertThat(java).contains(line);
            assertThat(typescript).contains(line);
        }
    }

    private void writeFixture(Path root) throws IOException {
        ArtifactJsonStore store = new ArtifactJsonStore(JSON);
        ObjectNode project = JSON.createObjectNode();
        ObjectNode workspaceNode = project.putArray("workspaces").addObject();
        workspaceNode.put("projectRoot", root.toString());
        workspaceNode.putObject("defaults").putObject("orchestrator")
                .put("resumePollMax", 1).put("resumePollIntervalMs", 10).put("resumePollTimeoutMs", 100);
        workspaceNode.putArray("executionProfiles").addObject().put("executionProfile", "load-suite")
                .put("executionPolicy", "stop_on_fail").put("suiteType", "performance")
                .putArray("plans").addObject().put("order", 1).put("planName", "load");
        store.write(root.resolve(".mcpjvm/demo/projects.json"), project);
        store.write(root.resolve(".mcpjvm/demo/plans/performance/load/metadata.json"),
                JSON.createObjectNode().put("suiteType", "performance")
                        .putObject("execution").put("intent", "performance"));
        ObjectNode contract = JSON.createObjectNode();
        ObjectNode entrypoint = contract.putArray("entrypoints").addObject();
        entrypoint.putObject("transport").put("protocol", "http")
                .put("baseUrl", "http://127.0.0.1:8080");
        entrypoint.putObject("request").put("method", "GET").put("path", "/health");
        contract.putObject("workloadProvider").put("type", "jmeter").put("mode", "generated_http");
        contract.putObject("observationTargets").put("baseUrl", "http://127.0.0.1:9195")
                .putArray("requiredLineHits").add("example.Controller#health:1");
        contract.putObject("loadModel").put("mode", "concurrency").put("concurrency", 10)
                .put("rampUpSeconds", 2).put("durationSeconds", 30);
        contract.putObject("successCriteria").put("maxErrorRatePct", 1)
                .put("minThroughputPerSec", 5).put("p95LatencyMs", 1200);
        store.write(root.resolve(".mcpjvm/demo/plans/performance/load/contract.json"), contract);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("tools/contracts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("repository root could not be located");
        return current;
    }
}
