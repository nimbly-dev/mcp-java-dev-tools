package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactWorkspaceProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultArtifacts;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry.ProbeRegistration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Focused lifecycle and Fail-Closed tests for the Artifact Core Feature. */
class ArtifactManagementOperationsTest {

    @TempDir
    Path workspace;

    private ObjectMapper mapper;
    private ArtifactManagementTestHarness operations;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        ArtifactWorkspaceProvider provider = () -> Optional.of(workspace);
        operations = new ArtifactManagementTestHarness(
                provider,
                new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(),
                mapper);
    }

    @Test
    void upsertAndReadProbeConfigUseWorkspaceContainedArtifact() {
        ObjectNode payload = mapper.createObjectNode();
        payload.putArray("probes").addObject().put("id", "line");
        ArtifactManagementResult upsert = operations.upsertProbeConfig(request(
                ArtifactType.PROBE_CONFIG,
                ArtifactAction.UPSERT,
                Map.of("payload", payload)));

        ArtifactManagementResult read = operations.readProbeConfig(request(
                ArtifactType.PROBE_CONFIG,
                ArtifactAction.READ,
                Map.of()));

        assertThat(upsert.status()).isEqualTo("ok");
        assertThat(read.status()).isEqualTo("ok");
        assertThat(read.details()).containsKey("artifact");
        assertThat(Files.isRegularFile(workspace.resolve(".mcpjvm/probe-config.json"))).isTrue();
    }

    @Test
    void invalidProjectSelectorFailsClosedWithoutEscapingWorkspace() {
        ArtifactManagementResult result = operations.upsertProjectContext(request(
                ArtifactType.PROJECT_CONTEXT,
                ArtifactAction.UPSERT,
                Map.of("projectName", "../outside", "payload", validProject())));

        assertThat(result.reasonCode()).isEqualTo("artifact_path_segment_invalid");
        assertThat(Files.exists(workspace.getParent().resolve("outside"))).isFalse();
    }

    @Test
    void missingWorkspaceIsDeterministicallyBlocked() {
        ArtifactManagementTestHarness missing = new ArtifactManagementTestHarness(
                () -> Optional.empty(),
                new ArtifactJsonStore(mapper),
                new SqliteRunStateStore(),
                mapper);

        ArtifactManagementResult result = missing.listProjects(request(
                ArtifactType.PROJECT_CONTEXT,
                ArtifactAction.LIST,
                Map.of()));

        assertThat(result.reasonCode()).isEqualTo("workspace_context_missing");
        assertThat(result.status()).isEqualTo("workspace_context_missing");
    }

    @Test
    void runResultRebuildCreatesAndQueriesProjectOwnedSqliteStore() {
        ArtifactManagementResult rebuilt = operations.rebuildRunResults(request(
                ArtifactType.RUN_RESULT,
                ArtifactAction.REBUILD,
                Map.of("projectName", "demo")));

        ArtifactManagementResult queried = operations.queryRunResults(request(
                ArtifactType.RUN_RESULT,
                ArtifactAction.QUERY,
                Map.of("projectName", "demo")));

        assertThat(rebuilt.status()).isEqualTo("ok");
        assertThat(Files.isRegularFile(workspace.resolve(".mcpjvm/demo/run-state.sqlite"))).isTrue();
        assertThat(queried.status()).isEqualTo("ok");
        assertThat(queried.details()).containsKey("query");
        Object query = queried.details().get("query");
        assertThat(query).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) query).get("status")).isEqualTo("available");
    }

    @Test
    void performancePlanRoundTripsWithPerformanceSpecificValidation() {
        ArtifactManagementResult upsert = operations.upsertPlan(request(
                ArtifactType.PERFORMANCE_PLAN,
                ArtifactAction.UPSERT,
                Map.of("projectName", "demo", "planName", "load", "payload", performancePlan())) ,
                "performance");

        ArtifactManagementResult read = operations.readPlan(request(
                ArtifactType.PERFORMANCE_PLAN,
                ArtifactAction.READ,
                Map.of("projectName", "demo", "planName", "load")), "performance");

        assertThat(upsert.status()).isEqualTo("ok");
        assertThat(read.status()).isEqualTo("ok");
        assertThat(read.details()).containsEntry("planName", "load");
    }

    @Test
    void symlinkedArtifactRootCannotEscapeWorkspace() throws Exception {
        Path outside = Files.createTempDirectory("artifact-outside");
        Path link = workspace.resolve(".mcpjvm");
        assumeDirectoryLink(link, outside);

        ArtifactManagementResult result = operations.upsertProbeConfig(request(
                ArtifactType.PROBE_CONFIG,
                ArtifactAction.UPSERT,
                Map.of("payload", mapper.createObjectNode().put("token", "secret"))));

        assertThat(result.reasonCode()).isEqualTo("artifact_path_symlink_escape");
        try (var entries = Files.list(outside)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void nestedPlanAndRunLinksCannotEscapeAfterOwnerPathAssembly() throws Exception {
        Path outsidePlan = Files.createTempDirectory("artifact-outside-plan");
        Path planLink = workspace.resolve(".mcpjvm/demo/plans/regression/escape-plan");
        Files.createDirectories(planLink.getParent());
        assumeDirectoryLink(planLink, outsidePlan);

        ArtifactManagementResult planResult = operations.upsertPlan(request(
                ArtifactType.REGRESSION_PLAN,
                ArtifactAction.UPSERT,
                Map.of("projectName", "demo", "planName", "escape-plan",
                        "payload", Map.of("metadata", Map.of(), "contract", Map.of()))), "regression");
        assertThat(planResult.reasonCode()).isEqualTo("artifact_path_symlink_escape");

        Path outsideRun = Files.createTempDirectory("artifact-outside-run");
        Files.writeString(outsideRun.resolve("execution.result.json"), "{\"status\":\"pass\"}");
        Path runLink = workspace.resolve(".mcpjvm/demo/plans/regression/normal/runs/escape-run");
        Files.createDirectories(runLink.getParent());
        assumeDirectoryLink(runLink, outsideRun);
        ArtifactManagementResult runResult = operations.readRunResult(request(
                ArtifactType.RUN_RESULT,
                ArtifactAction.READ,
                Map.of("projectName", "demo", "planName", "normal", "runId", "escape-run")));
        assertThat(runResult.reasonCode()).isEqualTo("artifact_path_symlink_escape");
    }

    @Test
    void executionExportsAreRunnableArtifactsForAllPublishedModes() throws Exception {
        ObjectNode project = validProject();
        ((ObjectNode) project.path("workspaces").get(0)).putArray("executionProfiles").addObject()
                .put("executionProfile", "smoke")
                .put("executionPolicy", "stop_on_fail")
                .put("suiteType", "regression")
                .putArray("plans").addObject().put("order", 1).put("planName", "health");
        ((ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0)
                .path("plans").get(0)).put("onFail", "inherit");
        ArtifactManagementResult projectUpsert = operations.upsertProjectContext(request(
                ArtifactType.PROJECT_CONTEXT, ArtifactAction.UPSERT,
                Map.of("projectName", "demo", "payload", project)));
        assertThat(projectUpsert.status()).as(projectUpsert.reasonCode() + ": " + projectUpsert.reason())
                .isEqualTo("ok");
        JsonNode persistedProject = mapper.readTree(Files.readString(
                workspace.resolve(".mcpjvm/demo/projects.json")));
        assertThat(persistedProject.path("workspaces").get(0).path("executionProfiles").get(0)
                .path("executionProfile").asText()).isEqualTo("smoke");
        ObjectNode contract = mapper.createObjectNode();
        contract.putArray("targets").addObject();
        contract.putArray("steps").addObject()
                .put("order", 1).put("id", "health")
                .put("protocol", "http")
                .putObject("transport").putObject("http")
                .put("method", "GET").put("url", "http://127.0.0.1:9196/health");
        operations.upsertPlan(request(
                ArtifactType.REGRESSION_PLAN, ArtifactAction.UPSERT,
                Map.of("projectName", "demo", "planName", "health",
                        "payload", Map.of("metadata", Map.of(), "contract", contract))), "regression");
        for (String mode : List.of("ps1", "sh", "postman")) {
            ArtifactManagementResult generated = operations.generateExport(request(
                    ArtifactType.EXECUTION_EXPORT,
                    ArtifactAction.GENERATE,
                    Map.of("projectName", "demo", "mode", mode, "planName", "health",
                            "executionProfile", "smoke")));
            assertThat(generated.status()).isEqualTo("ok");
            String exportId = String.valueOf(generated.details().get("exportId"));
            Path export = workspace.resolve(".mcpjvm/demo/exports").resolve(exportId);
            Path replay = export.resolve(mode.equals("ps1") ? "replay.ps1"
                    : mode.equals("sh") ? "replay.sh" : "replay.postman.json");
            String content = Files.readString(replay);
            assertThat(content).doesNotStartWith("{\n  \"exportId\"");
            assertThat(content).contains("http://127.0.0.1:9196/health")
                    .doesNotContain("127.0.0.1:8080/mcp");
            if (mode.equals("ps1")) {
                assertThat(content).contains("Invoke-WebRequest")
                        .contains("[regex]::Replace($Value, '\\{\\{");
            }
            if (mode.equals("sh")) {
                assertThat(content).contains("curl --fail")
                        .contains("while [[ \"$value\" =~ \\{\\{");
            }
            if (mode.equals("postman")) assertThat(content).contains("collection/v2.1.0");
        }
    }

    @Test
    void executionExportOptionsChangeReplaySectionsBindingsAndSecretMaterialization() throws Exception {
        ObjectNode project = validProject();
        ObjectNode workspaceArtifact = (ObjectNode) project.path("workspaces").get(0);
        workspaceArtifact.put("envFile", ".mcpjvm/demo/.env");
        workspaceArtifact.putObject("sessionExport")
                .put("includeRuntimeStartup", false)
                .put("includeHealthcheckGate", false);
        workspaceArtifact.putObject("variables").putObject("contextBindings")
                .put("auth.bearer", "AUTH_TOKEN")
                .put("apiBaseUrl", "API_BASE_URL");
        workspaceArtifact.putArray("runtimeContexts").addObject()
                .put("name", "local").put("mode", "terminal").put("autoStart", true)
                .putArray("startups").addObject().put("name", "gateway").put("command", "echo")
                .putArray("args").add("runtime-started");
        workspaceArtifact.putArray("externalSystems").addObject().put("name", "gateway")
                .putArray("healthChecks").addObject().put("id", "ready").put("type", "http")
                .put("url", "http://127.0.0.1:9196/health").put("required", true);
        workspaceArtifact.putArray("scripts").addObject()
                .put("name", "setup").put("phase", "postHealthcheck").put("command", "sh")
                .put("envFileArg", "--env-file").putArray("args").add("setup.sh");
        workspaceArtifact.putArray("executionProfiles").addObject()
                .put("executionProfile", "smoke").put("executionPolicy", "stop_on_fail")
                .put("suiteType", "regression")
                .put("runtimeContextName", "local").putArray("scriptRefs").addObject()
                .put("name", "setup").put("phase", "postHealthcheck");
        ((ObjectNode) workspaceArtifact.path("executionProfiles").get(0)).putArray("plans")
                .addObject().put("order", 1).put("planName", "health");
        ArtifactManagementResult configured = operations.upsertProjectContext(request(
                ArtifactType.PROJECT_CONTEXT, ArtifactAction.UPSERT,
                Map.of("projectName", "demo", "payload", project)));
        assertThat(configured.status()).as(configured.reasonCode() + ": " + configured.reason())
                .isEqualTo("ok");
        Path envFile = workspace.resolve(".mcpjvm/demo/.env");
        Files.createDirectories(envFile.getParent());
        Files.writeString(envFile, "AUTH_TOKEN=source-secret\nAPI_BASE_URL=http://source\n");
        Files.writeString(workspace.resolve("setup.sh"), "#!/usr/bin/env bash\necho setup\n");

        ObjectNode contract = mapper.createObjectNode();
        contract.putArray("steps").addObject().put("order", 1).put("id", "health")
                .put("protocol", "http").putObject("transport").putObject("http")
                .put("method", "GET").put("url", "${apiBaseUrl}/health")
                .putObject("headers").put("X-Request-Context", "${auth.bearer}");
        operations.upsertPlan(request(
                ArtifactType.REGRESSION_PLAN, ArtifactAction.UPSERT,
                Map.of("projectName", "demo", "planName", "health",
                        "payload", Map.of("metadata", Map.of(), "contract", contract))), "regression");

        Map<String, Object> common = new java.util.LinkedHashMap<>();
        common.put("projectName", "demo");
        common.put("mode", "sh");
        common.put("planName", "health");
        common.put("executionProfile", "smoke");
        common.put("when", "nightly");
        common.put("contextBindings", Map.of("auth.bearer", "CUSTOM_TOKEN", "apiBaseUrl", "CUSTOM_BASE"));
        common.put("contextValues", Map.of("auth.bearer", "request-secret", "apiBaseUrl", "http://request"));
        common.put("includeRuntimeStartup", false);
        common.put("includeHealthcheckGate", false);
        common.put("includeResolvedSecrets", false);
        ArtifactManagementResult redacted = operations.generateExport(request(
                ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE, common));
        assertThat(redacted.status()).as(redacted.reasonCode() + ": " + redacted.reason()).isEqualTo("ok");
        Path redactedExport = workspace.resolve(".mcpjvm/demo/exports")
                .resolve(String.valueOf(redacted.details().get("exportId")));
        String redactedReplay = Files.readString(redactedExport.resolve("replay.sh"));
        String redactedEnv = Files.readString(redactedExport.resolve("project.env"));
        assertThat(redactedReplay).contains("runtime startup skipped by export options")
                .contains("healthcheck gate skipped by export options")
                .contains("CUSTOM_BASE").contains("CUSTOM_TOKEN")
                .contains("postHealthcheck setup").contains("$__MCPJVM_PROJECT_ENV")
                .contains("nightly").doesNotContain("request-secret");
        assertThat(redactedEnv).contains("CUSTOM_TOKEN=").doesNotContain("request-secret", "source-secret")
                .doesNotContain("SENSITIVE EXPORT");
        assertThat(Files.isRegularFile(redactedExport.resolve("scripts/setup/setup.sh"))).isTrue();

        common.put("includeRuntimeStartup", true);
        common.put("includeHealthcheckGate", true);
        common.put("includeResolvedSecrets", true);
        ArtifactManagementResult resolved = operations.generateExport(request(
                ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE, common));
        Path resolvedExport = workspace.resolve(".mcpjvm/demo/exports")
                .resolve(String.valueOf(resolved.details().get("exportId")));
        assertThat(Files.readString(resolvedExport.resolve("replay.sh")))
                .contains("runtime-started").contains("curl --fail")
                .contains("CUSTOM_BASE").contains("CUSTOM_TOKEN");
        assertThat(Files.readString(resolvedExport.resolve("project.env")))
                .contains("SENSITIVE EXPORT").contains("CUSTOM_TOKEN=request-secret")
                .contains("CUSTOM_BASE=http://request");
    }

    @Test
    void probeUpsertReloadsTheActiveRegistry() {
        AtomicInteger reloads = new AtomicInteger();
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> Optional.of(workspace), new ArtifactJsonStore(mapper), new SqliteRunStateStore(), mapper);
        ProbeConfigArtifacts owner = new ProbeConfigArtifacts(support, () -> {
            reloads.incrementAndGet();
            return new ProbeRegistry(List.of(new ProbeRegistration("live", "http://127.0.0.1:1")));
        });

        ArtifactManagementResult result = owner.upsert(request(
                ArtifactType.PROBE_CONFIG, ArtifactAction.UPSERT,
                Map.of("payload", mapper.createObjectNode().put("probes", "configured"))));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(reloads).hasValue(1);
        assertThat(result.details()).containsEntry("reloadApplied", true)
                .containsEntry("activeProbeCount", 1);
    }

    @Test
    void probeReloadRefreshesTheActiveRegistryWithoutDuplicateStatusOwnership() {
        ArtifactManagementSupport support = new ArtifactManagementSupport(
                () -> Optional.of(workspace), new ArtifactJsonStore(mapper), new SqliteRunStateStore(), mapper);
        ProbeConfigArtifacts owner = new ProbeConfigArtifacts(support,
                () -> new ProbeRegistry(List.of(new ProbeRegistration("live", "http://127.0.0.1:1"))));
        try {
            Files.createDirectories(workspace.resolve(".mcpjvm"));
            Files.writeString(workspace.resolve(".mcpjvm/probe-config.json"), "{}\n");
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }

        ArtifactManagementResult result = owner.reload(request(
                ArtifactType.PROBE_CONFIG, ArtifactAction.RELOAD, Map.of()));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.details()).doesNotContainKey("status");
        assertThat(result.details()).containsEntry("reloadApplied", true)
                .containsEntry("activeProbeCount", 1);
    }

    private void assumeDirectoryLink(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows directory junctions provide the same reparse-point containment proof.
        }
        Files.deleteIfExists(link);
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Assumptions.assumeTrue(false, "directory links are unavailable in this environment");
        }
        Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        try {
            if (process.waitFor() != 0) {
                Assumptions.assumeTrue(false, "directory junctions are unavailable in this environment");
            }
        } finally {
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();
            process.destroy();
        }
    }

    private ObjectNode performancePlan() {
        try (var stream = getClass().getResourceAsStream("/artifactmanagement/performance-plan-parity.json")) {
            assertThat(stream).isNotNull();
            return (ObjectNode) mapper.readTree(stream);
        } catch (IOException exception) {
            throw new AssertionError("performance parity fixture could not be read", exception);
        }
    }

    private ObjectNode validProject() {
        ObjectNode project = mapper.createObjectNode();
        project.putArray("workspaces").addObject()
                .put("projectRoot", workspace.toString())
                .set("defaults", mapper.createObjectNode()
                        .set("orchestrator", mapper.createObjectNode()
                                .put("resumePollMax", 1)
                                .put("resumePollIntervalMs", 10)
                                .put("resumePollTimeoutMs", 100)));
        return project;
    }

    private ArtifactManagementRequest request(
            ArtifactType type,
            ArtifactAction action,
            Map<String, Object> input) {
        return new ArtifactManagementRequest(type, action, mapper.valueToTree(input));
    }

    /** Test-only composition of family owners; production has no compatibility façade. */
    private static final class ArtifactManagementTestHarness {
        private final ProbeConfigArtifacts probe;
        private final ProjectContextArtifacts project;
        private final PlanArtifacts plans;
        private final RunResultArtifacts runs;
        private final ExecutionExportArtifacts exports;

        private ArtifactManagementTestHarness(
                ArtifactWorkspaceProvider provider,
                ArtifactJsonStore store,
                SqliteRunStateStore state,
                ObjectMapper mapper) {
            ArtifactManagementSupport support = new ArtifactManagementSupport(provider, store, state, mapper);
            probe = new ProbeConfigArtifacts(support);
            project = new ProjectContextArtifacts(support);
            plans = new PlanArtifacts(support);
            runs = new RunResultArtifacts(support);
            exports = new ExecutionExportArtifacts(support);
        }

        private ArtifactManagementResult upsertProbeConfig(ArtifactManagementRequest r) { return probe.upsert(r); }
        private ArtifactManagementResult readProbeConfig(ArtifactManagementRequest r) { return probe.read(r); }
        private ArtifactManagementResult upsertProjectContext(ArtifactManagementRequest r) { return project.upsert(r); }
        private ArtifactManagementResult listProjects(ArtifactManagementRequest r) { return project.list(r); }
        private ArtifactManagementResult rebuildRunResults(ArtifactManagementRequest r) { return runs.rebuild(r); }
        private ArtifactManagementResult queryRunResults(ArtifactManagementRequest r) { return runs.query(r); }
        private ArtifactManagementResult readRunResult(ArtifactManagementRequest r) { return runs.read(r); }
        private ArtifactManagementResult generateExport(ArtifactManagementRequest r) { return exports.generate(r); }
        private ArtifactManagementResult upsertPlan(ArtifactManagementRequest r, String suite) { return plans.upsert(r, suite); }
        private ArtifactManagementResult readPlan(ArtifactManagementRequest r, String suite) { return plans.read(r, suite); }
    }
}
