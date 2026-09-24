package com.nimbly.mcpjavadevtools.server.compatibility.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactJsonStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.SqliteRunStateStore;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation.ArtifactOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.persistence.TrustedDirectSuiteRun;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.DefaultProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.actuate.impl.ProbeActuateAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.capture.impl.ProbeCaptureAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.check.impl.ProbeCheckAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.LocalProbeProfilerOutputStore;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler.impl.ProbeProfilerAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.reset.impl.ProbeResetAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.status.impl.ProbeStatusAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.waitforhit.impl.ProbeWaitForHitAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.endpoint.HttpProbeEndpointClient;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointConfiguration;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointLimits;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeEndpointPaths;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestBounds;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.endpoint.ProbeRequestPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeResponseCompactionPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.registry.ProbeRegistryProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.routing.ProbeTargetResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.DefaultPerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.impl.ExecutePerformancePlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution.PerformancePlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.operation.TrustedPerformanceSuiteRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.DefaultJmeterProcessRunner;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterExecutableResolver;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJmxRenderer;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterJtlCollector;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter.JmeterWorkloadExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.DefaultRegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.ExecuteRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.PreflightRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.operation.TrustedRegressionSuiteRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.DefaultSecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.action.impl.ExecuteSecurityPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.execution.SecurityPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.knowledge.SecurityKnowledgeCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.operation.TrustedSecuritySuiteRegistrations;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.DefaultTransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.impl.ExecuteTransportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProviderRegistry;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.custom.CustomTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.grpc.GrpcTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRedirectResponseExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpRequestValidator;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpSensitiveDataRedactor;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http.HttpTransportSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.kafka.KafkaTransportProvider;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Raw-STDIO orchestration provenance evidence for the four direct Suite CDE operations. */
class SuiteOrchestrationLegacyStdioIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    @TempDir
    Path workspace;

    @Test
    void packagedServerUsesOrchestrationWithoutAdvertisingStandaloneSuiteTools() throws Exception {
        HttpServer target = startTarget();
        int workloadPort = freePort();
        try (SidecarTargetProcess sidecarTarget = SidecarTargetProcess.start(workloadPort);
                DirectSuiteOperations direct = DirectSuiteOperations.create(workspace)) {
            writeBaseProject();
            try (ServerProcess server = ServerProcess.start(
                    jarPath(), workspace, "http://127.0.0.1:" + workloadPort + "/workload")) {
                initialize(server);
                assertInventory(server);
                assertRegressionProvenance(server, direct, target.getAddress().getPort());
                int probePort = freePort();
                attachSidecar(server, sidecarTarget, probePort);
                writeProbeRegistry(probePort);
                assertPerformanceProvenance(
                        server, direct, workloadPort, probePort, sidecarTarget.strictLineKey());
                assertSecurityProvenance(server, direct, target.getAddress().getPort());
                awaitWorkloadDrivers();
                deactivateSidecar(server, sidecarTarget);
            }
        } finally {
            target.stop(0);
        }
    }

    private static void assertInventory(ServerProcess server) throws Exception {
        server.send(request(2, "tools/list", Map.of()));
        JsonNode tools = server.responseFor(2).path("result").path("tools");
        List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.path("name").asText());
        }
        assertThat(names).contains("execution_orchestration")
                .doesNotContain("regression_suite", "performance_suite", "security_suite");
    }

    private void assertRegressionProvenance(
            ServerProcess server, DirectSuiteOperations direct, int port) throws Exception {
        writeRegressionPlan(port);
        JsonNode result = execute(server, 3, "regression-smoke", "stdio-regression");
        JsonNode resumedJava = execute(server, 9, "regression-smoke", "stdio-regression");
        JsonNode preflight = direct.execute(
                "regression_suite.preflight", directInput("regression", "regression-smoke", "direct-regression"));
        JsonNode directResult = direct.execute(
                "regression_suite.execute_plan",
                directInput("regression", "regression-smoke", "direct-regression"));
        JsonNode typescriptResult = runTypeScriptOrchestration("regression-smoke");
        JsonNode resumedTypescript = runTypeScriptOrchestration(
                "regression-smoke", typescriptResult.path("suiteRunId").asText());

        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("pass");
        assertThat(result.path("planRuns").toString()).contains("regression-smoke");
        assertThat(preflight.path("status").asText()).as(preflight.toPrettyString()).isEqualTo("ready");
        assertRegressionPreflightParity(preflight, typescriptResult);
        assertDirectParity(result, directResult, "runStatus");
        assertDirectPersisted("regression", "regression-smoke", "direct-regression", result, directResult);
        assertThat(jsonValue(direct.execute("regression_suite.execute_plan",
                directInput("regression", "regression-smoke", "direct-regression"))))
                .isEqualTo(jsonValue(directResult));
        assertStableStepParity(
                result.path("planRuns").get(0).path("details").path("steps"),
                directResult.path("details").path("steps"));
        assertOrchestrationParity(result, resumedJava, "runStatus");
        assertOrchestrationParity(result, typescriptResult, "runStatus");
        assertOrchestrationParity(typescriptResult, resumedTypescript, "runStatus");
        assertRegressionArtifactParity(result, typescriptResult);
        assertPersistedResult("stdio-regression", "regression-smoke");
        assertPersistedResult(typescriptResult.path("suiteRunId").asText(), "regression-smoke");
    }

    private void assertPerformanceProvenance(
            ServerProcess server,
            DirectSuiteOperations direct,
            int targetPort,
            int probePort,
            String strictLineKey) throws Exception {
        writePerformancePlan(targetPort, probePort, strictLineKey);
        JsonNode result = execute(server, 4, "performance-smoke", "stdio-performance");
        JsonNode resumedJava = execute(server, 10, "performance-smoke", "stdio-performance");
        JsonNode directResult = direct.execute(
                "performance_suite.execute_plan",
                directInput("performance", "performance-smoke", "direct-performance"));
        JsonNode typescriptResult = runTypeScriptOrchestration("performance-smoke");
        JsonNode resumedTypescript = runTypeScriptOrchestration(
                "performance-smoke", typescriptResult.path("suiteRunId").asText());

        assertLiveLineHit(server, probePort, strictLineKey);
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("pass");
        assertThat(typescriptResult.path("status").asText())
                .as(typescriptResult.toPrettyString() + System.lineSeparator()
                        + persistedPlanResult("performance", "performance-smoke", typescriptResult))
                .isEqualTo("pass");
        assertThat(result.path("planRuns").toString())
                .contains("thresholdResults", strictLineKey);
        assertDirectParity(result, directResult, "runStatus", "thresholdResults", "requiredLineHits");
        assertDirectPersisted("performance", "performance-smoke", "direct-performance", result, directResult);
        assertThat(jsonValue(direct.execute("performance_suite.execute_plan",
                directInput("performance", "performance-smoke", "direct-performance"))))
                .isEqualTo(jsonValue(directResult));
        assertOrchestrationParity(result, typescriptResult, "runStatus");
        assertLegacyTerminalResumeGap(resumedJava, resumedTypescript);
        assertPerformanceArtifactParity(result, typescriptResult, strictLineKey);
        assertPersistedResult("stdio-performance", "performance-smoke");
        assertPersistedResult(typescriptResult.path("suiteRunId").asText(), "performance-smoke");
    }

    private static void assertLiveLineHit(
            ServerProcess server, int probePort, String strictLineKey) throws Exception {
        JsonNode result = server.call(8, "probe", Map.of(
                "action", "status",
                "input", Map.of(
                        "baseUrl", "http://127.0.0.1:" + probePort,
                        "key", strictLineKey)));
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("ok");
        assertThat(result.path("details").path("executionHit").asText()).isEqualTo("line_hit");
        JsonNode evidence = result.path("details").path("response").path("json");
        assertThat(evidence.path("key").asText()).isEqualTo(strictLineKey);
        assertThat(evidence.path("hitCount").asLong()).isPositive();
    }

    private static void attachSidecar(
            ServerProcess server, SidecarTargetProcess target, int probePort) throws Exception {
        JsonNode result = server.call(6, "jvm_lifecycle", Map.of(
                "action", "attach",
                "input", Map.of(
                        "pid", Long.toString(target.pid()),
                        "expectedProcessStartEpochMs", target.processStartEpochMs(),
                        "confirm", true,
                        "probeHost", "127.0.0.1",
                        "probePort", probePort,
                        "include", SidecarTargetMain.class.getName())));
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("ok");
        assertThat(result.path("reasonCode").asText()).isEqualTo("active");
    }

    private static void deactivateSidecar(
            ServerProcess server, SidecarTargetProcess target) throws Exception {
        JsonNode result = server.call(7, "jvm_lifecycle", Map.of(
                "action", "deactivate",
                "input", Map.of(
                        "pid", Long.toString(target.pid()),
                        "expectedProcessStartEpochMs", target.processStartEpochMs(),
                        "confirm", true)));
        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("ok");
        assertThat(result.path("reasonCode").asText()).isEqualTo("deactivated");
        assertThat(target.isAlive()).isTrue();
    }

    private void assertSecurityProvenance(
            ServerProcess server, DirectSuiteOperations direct, int port) throws Exception {
        writeSecurityPlan(port);
        JsonNode result = execute(server, 5, "security-smoke", "stdio-security");
        JsonNode resumedJava = execute(server, 11, "security-smoke", "stdio-security");
        JsonNode directResult = direct.execute(
                "security_suite.execute_plan", directInput("security", "security-smoke", "direct-security"));
        JsonNode typescriptResult = runTypeScriptOrchestration("security-smoke");
        JsonNode resumedTypescript = runTypeScriptOrchestration(
                "security-smoke", typescriptResult.path("suiteRunId").asText());

        assertThat(result.path("status").asText()).as(result.toPrettyString()).isEqualTo("pass");
        assertThat(typescriptResult.path("status").asText())
                .as(typescriptResult.toPrettyString() + System.lineSeparator()
                        + persistedPlanResult("security", "security-smoke", typescriptResult))
                .isEqualTo("pass");
        assertThat(result.path("planRuns").toString()).contains("coverage").contains("findings");
        assertThat(result.toString()).doesNotContain("Bearer should-not-escape");
        assertThat(directResult.toString()).doesNotContain("Bearer should-not-escape");
        assertDirectParity(result, directResult, "runStatus", "coverage", "findings");
        assertDirectPersisted("security", "security-smoke", "direct-security", result, directResult);
        assertThat(jsonValue(direct.execute("security_suite.execute_plan",
                directInput("security", "security-smoke", "direct-security"))))
                .isEqualTo(jsonValue(directResult));
        assertOrchestrationParity(result, typescriptResult, "runStatus");
        assertLegacyTerminalResumeGap(resumedJava, resumedTypescript);
        assertSecurityArtifactParity(result, typescriptResult);
        assertPersistedResult("stdio-security", "security-smoke");
        assertPersistedResult(typescriptResult.path("suiteRunId").asText(), "security-smoke");
    }

    private ObjectNode directInput(String suiteType, String planName, String suiteRunId) throws Exception {
        ObjectNode input = JSON.createObjectNode();
        input.put("projectName", "demo");
        input.put("planName", planName);
        input.put("executionProfile", planName);
        input.put("suiteRunId", suiteRunId);
        return input;
    }

    private static void assertDirectParity(
            JsonNode orchestration, JsonNode direct, String... stableDetailFields) {
        JsonNode planRun = orchestration.path("planRuns").get(0);
        assertThat(direct.path("status").asText()).as(direct.toPrettyString())
                .isEqualTo(planRun.path("status").asText());
        assertThat(direct.path("reasonCode").asText()).isEqualTo(planRun.path("reasonCode").asText());
        for (String field : stableDetailFields) {
            assertThat(jsonValue(direct.path("details").path(field))).as(field)
                    .isEqualTo(jsonValue(planRun.path("details").path(field)));
        }
    }

    private void assertDirectPersisted(String suiteType, String planName, String suiteRunId,
            JsonNode orchestration, JsonNode direct) throws Exception {
        Path run = planRoot(suiteType, planName).resolve("runs").resolve(suiteRunId + "-1");
        Path result = run.resolve("execution.result.json");
        assertThat(result).isRegularFile();
        JsonNode artifact = JSON.readTree(Files.readString(result));
        JsonNode orchestrationRun = orchestration.path("planRuns").get(0);
        assertThat(artifact.path("suiteRunId").asText()).isEqualTo(suiteRunId);
        assertThat(artifact.path("executionProfile").asText()).isEqualTo(planName);
        assertThat(artifact.path("reasonCode").asText()).isEqualTo(orchestrationRun.path("reasonCode").asText());
        assertThat(artifact.path("details").path("runStatus").asText())
                .isEqualTo(orchestrationRun.path("details").path("runStatus").asText());
        assertThat(jsonValue(artifact.path("directResult"))).isEqualTo(jsonValue(direct));
        if ("performance".equals(suiteType)) {
            assertThat(run.resolve("workload.jmeter.jmx")).isRegularFile();
            assertThat(run.resolve("workload.jmeter.jtl")).isRegularFile();
            assertThat(run.resolve("workload.jmeter.log")).isRegularFile();
        }
        if ("security".equals(suiteType)) {
            assertThat(artifact.toString()).doesNotContain("Bearer should-not-escape");
            assertThat(artifact.path("details").path("coverage").isObject()).isTrue();
            assertThat(artifact.path("details").path("findings").isArray()).isTrue();
        }
    }

    private static void assertOrchestrationParity(
            JsonNode javaResult, JsonNode typescriptResult, String... stableDetailFields) {
        assertThat(typescriptResult.path("status").asText()).as(typescriptResult.toPrettyString())
                .isEqualTo(javaResult.path("status").asText());
        assertThat(effectiveReasonCode(typescriptResult))
                .isEqualTo(effectiveReasonCode(javaResult));
        JsonNode javaRun = javaResult.path("planRuns").get(0);
        JsonNode typescriptRun = typescriptResult.path("planRuns").get(0);
        assertThat(effectiveRunStatus(typescriptRun)).isEqualTo(effectiveRunStatus(javaRun));
        assertThat(effectiveReasonCode(typescriptRun)).isEqualTo(effectiveReasonCode(javaRun));
        for (String field : stableDetailFields) {
            assertThat(jsonValue(stableRunField(typescriptRun, field))).as(field)
                    .isEqualTo(jsonValue(stableRunField(javaRun, field)));
        }
    }

    private static void assertStableStepParity(JsonNode orchestrationSteps, JsonNode directSteps) {
        assertThat(directSteps).hasSize(orchestrationSteps.size());
        for (int index = 0; index < orchestrationSteps.size(); index++) {
            JsonNode orchestrationStep = orchestrationSteps.get(index);
            JsonNode directStep = directSteps.get(index);
            for (String field : List.of("order", "id", "status", "reasonCode", "statusCode")) {
                assertThat(jsonValue(directStep.path(field))).as("step[%s].%s", index, field)
                        .isEqualTo(jsonValue(orchestrationStep.path(field)));
            }
        }
    }

    private static void assertLegacyTerminalResumeGap(JsonNode javaResult, JsonNode typescriptResult) {
        assertThat(javaResult.path("status").asText()).as(javaResult.toPrettyString()).isEqualTo("pass");
        assertThat(typescriptResult.path("status").asText()).as(typescriptResult.toPrettyString())
                .isEqualTo("suite_resume_evidence_missing");
        assertThat(typescriptResult.path("reasonCode").asText())
                .isEqualTo("suite_resume_evidence_missing");
    }

    private static Object jsonValue(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> values = new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> values.put(entry.getKey(), jsonValue(entry.getValue())));
            return values;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(jsonValue(value)));
            return values;
        }
        if (node.isNumber()) {
            return node.decimalValue().stripTrailingZeros();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return null;
    }

    private static String effectiveRunStatus(JsonNode planRun) {
        JsonNode runStatus = stableRunField(planRun, "runStatus");
        return runStatus.isMissingNode() ? planRun.path("status").asText() : runStatus.asText();
    }

    private static String effectiveReasonCode(JsonNode result) {
        String reasonCode = result.path("reasonCode").asText();
        String status = result.path("status").asText();
        return reasonCode.isBlank() && ("pass".equals(status) || "executed".equals(status))
                ? "ok" : reasonCode;
    }

    private static JsonNode stableRunField(JsonNode planRun, String field) {
        JsonNode nested = planRun.path("details").path(field);
        return nested.isMissingNode() ? planRun.path(field) : nested;
    }

    private void assertRegressionPreflightParity(JsonNode directPreflight, JsonNode typescript)
            throws Exception {
        JsonNode artifact = persistedPlanResultNode("regression", "regression-smoke", typescript);
        JsonNode typescriptPreflight = artifact.path("preflight");
        assertThat(typescriptPreflight.path("status").asText()).as(artifact.toPrettyString())
                .isEqualTo(directPreflight.path("status").asText());
        assertThat(typescriptPreflight.path("missing").size())
                .isEqualTo(directPreflight.path("details").path("missing").size());
    }

    private void assertRegressionArtifactParity(JsonNode javaResult, JsonNode typescriptResult)
            throws Exception {
        JsonNode javaSteps = javaResult.path("planRuns").get(0).path("details").path("steps");
        JsonNode artifact = persistedPlanResultNode("regression", "regression-smoke", typescriptResult);
        assertThat(artifact.path("status").asText()).isEqualTo("pass");
        assertThat(artifact.path("steps")).hasSize(javaSteps.size());
        assertThat(artifact.path("steps").get(0).path("status").asText())
                .isEqualTo(javaSteps.get(0).path("status").asText());
        assertRunArtifactsExist("regression", "regression-smoke", typescriptResult,
                "context.resolved.json", "execution.result.json", "evidence.json");
    }

    private void assertPerformanceArtifactParity(
            JsonNode javaResult, JsonNode typescriptResult, String strictLineKey) throws Exception {
        JsonNode javaDetails = javaResult.path("planRuns").get(0).path("details");
        JsonNode artifact = persistedPlanResultNode("performance", "performance-smoke", typescriptResult);
        assertThat(artifact.path("status").asText()).isEqualTo(javaDetails.path("runStatus").asText());
        assertThat(artifact.path("metrics").path("totalRequests").asInt())
                .isEqualTo(javaDetails.path("metrics").path("totalRequests").asInt());
        assertThat(artifact.path("metrics").path("failedRequests").asInt())
                .isEqualTo(javaDetails.path("metrics").path("failedRequests").asInt());
        assertThresholdOutcomes(javaDetails.path("thresholdResults"), artifact.path("thresholdResults"));
        assertThat(artifact.path("requiredLineHits").toString()).contains(strictLineKey, "\"hit\":true");
        assertThat(javaDetails.path("requiredLineHits").toString()).contains(strictLineKey);
        assertThat(artifact.path("msta").path("status").asText())
                .isEqualTo(javaDetails.path("msta").path("status").asText());
        assertRunArtifactsExist("performance", "performance-smoke", typescriptResult,
                "execution.result.json", "evidence.json", "workload.jmeter.jmx",
                "workload.jmeter.jtl", "workload.jmeter.log");
    }

    private static void assertThresholdOutcomes(JsonNode javaThresholds, JsonNode typescriptThresholds) {
        for (String threshold : List.of("maxErrorRatePct", "minThroughputPerSec", "p95LatencyMs")) {
            assertThat(typescriptThresholds.path(threshold).path("pass").asBoolean()).as(threshold)
                    .isEqualTo(javaThresholds.path(threshold).path("pass").asBoolean());
        }
    }

    private void assertSecurityArtifactParity(JsonNode javaResult, JsonNode typescriptResult)
            throws Exception {
        JsonNode javaDetails = javaResult.path("planRuns").get(0).path("details");
        JsonNode artifact = persistedPlanResultNode("security", "security-smoke", typescriptResult);
        assertThat(artifact.path("status").asText()).isEqualTo(javaDetails.path("runStatus").asText());
        assertThat(artifact.path("coverage").path("complete").asBoolean())
                .isEqualTo(javaDetails.path("coverage").path("complete").asBoolean());
        assertThat(artifact.path("findings").size()).isEqualTo(javaDetails.path("findings").size());
        assertThat(artifact.toString()).doesNotContain("Bearer should-not-escape");
        assertRunArtifactsExist("security", "security-smoke", typescriptResult,
                "execution.result.json", "matrix.json", "coverage.json", "findings.json", "evidence.json");
    }

    private JsonNode runTypeScriptOrchestration(String executionProfile) throws Exception {
        return runTypeScriptOrchestration(executionProfile, null);
    }

    private JsonNode runTypeScriptOrchestration(
            String executionProfile, String suiteRunId) throws Exception {
        Path runner = workspace.resolve("typescript-orchestration-runner.cjs");
        Files.writeString(runner, """
                const { dispatchExecutionOrchestrationAction } =
                  require("@tools-feature-execution-orchestration");
                const { loadProbeRegistry } = require("@tools-core/probe-registry");
                const { CONFIG_DEFAULTS } = require("@tools-core/probe_defaults");
                const fs = require("node:fs");
                const path = require("node:path");
                (async () => {
                  const input = {
                    projectName: "demo",
                    executionProfile: process.argv[3],
                    maxPlansPerCall: 1
                  };
                  if (process.argv[4]) input.suiteRunId = process.argv[4];
                  const registryFile = path.join(
                    process.argv[2], ".mcpjvm", "probe-config.json");
                  const registry = fs.existsSync(registryFile) ? loadProbeRegistry({
                    filePath: registryFile, workspaceRootAbs: process.argv[2]
                  }) : undefined;
                  const probeConfig = registry ? {
                    probeBaseUrl: "",
                    probeStatusPath: CONFIG_DEFAULTS.PROBE_STATUS_PATH,
                    probeResetPath: CONFIG_DEFAULTS.PROBE_RESET_PATH,
                    probeActuatePath: CONFIG_DEFAULTS.PROBE_ACTUATE_PATH,
                    probeCapturePath: CONFIG_DEFAULTS.PROBE_CAPTURE_PATH,
                    probeProfilerPath: CONFIG_DEFAULTS.PROBE_PROFILER_PATH,
                    probeWaitMaxRetries: CONFIG_DEFAULTS.PROBE_WAIT_MAX_RETRIES,
                    probeWaitUnreachableRetryEnabled:
                      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_RETRY_ENABLED,
                    probeWaitUnreachableMaxRetries:
                      CONFIG_DEFAULTS.PROBE_WAIT_UNREACHABLE_MAX_RETRIES,
                    getProbeRegistry: () => registry
                  } : undefined;
                  const result = await dispatchExecutionOrchestrationAction({
                    workspaceRootAbs: process.argv[2],
                    ...(probeConfig ? { probeConfig } : {}),
                    request: { action: "execute", input }
                  });
                  process.stdout.write(JSON.stringify(result.structuredContent));
                })().catch((error) => {
                  process.stderr.write(String(error && error.stack ? error.stack : error));
                  process.exit(1);
                });
                """);
        Path repository = repositoryRoot();
        List<String> command = new ArrayList<>(List.of(
                "node", "--require", "ts-node/register", "--require", "tsconfig-paths/register",
                runner.toString(), workspace.toString(), executionProfile));
        if (suiteRunId != null) {
            command.add(suiteRunId);
        }
        Process process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        String[] lines = output.trim().split("\\R");
        return JSON.readTree(lines[lines.length - 1]);
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

    private static JsonNode execute(
            ServerProcess server, int id, String executionProfile, String suiteRunId) throws Exception {
        return server.call(id, "execution_orchestration", Map.of(
                "action", "execute",
                "input", Map.of(
                        "projectName", "demo",
                        "executionProfile", executionProfile,
                        "suiteRunId", suiteRunId,
                        "maxPlansPerCall", 1)));
    }

    private void assertPersistedResult(String suiteRunId, String planName) throws Exception {
        Path resultPath = workspace.resolve(".mcpjvm/demo/suite-runs")
                .resolve(suiteRunId).resolve("execution_orchestration.result.json");
        assertThat(resultPath).isRegularFile();
        JsonNode persisted = JSON.readTree(Files.readString(resultPath));
        assertThat(persisted.path("suiteRunId").asText()).isEqualTo(suiteRunId);
        assertThat(persisted.path("planRuns").toString()).contains(planName);
    }

    private String persistedPlanResult(String suiteType, String planName, JsonNode orchestration)
            throws Exception {
        Path result = planRunDirectory(suiteType, planName, orchestration).resolve("execution.result.json");
        return Files.exists(result) ? Files.readString(result) : "missing " + result;
    }

    private JsonNode persistedPlanResultNode(String suiteType, String planName, JsonNode orchestration)
            throws Exception {
        return JSON.readTree(persistedPlanResult(suiteType, planName, orchestration));
    }

    private void assertRunArtifactsExist(
            String suiteType, String planName, JsonNode orchestration, String... artifactNames)
            throws Exception {
        Path runDirectory = planRunDirectory(suiteType, planName, orchestration);
        for (String artifactName : artifactNames) {
            assertThat(runDirectory.resolve(artifactName)).as(artifactName).isRegularFile();
        }
    }

    private Path planRunDirectory(String suiteType, String planName, JsonNode orchestration)
            throws Exception {
        String runId = orchestration.path("planRuns").get(0).path("runId").asText();
        return planRoot(suiteType, planName).resolve("runs").resolve(runId);
    }

    private void writeBaseProject() throws Exception {
        Path projectRoot = workspace.resolve(".mcpjvm/demo");
        Files.createDirectories(projectRoot);
        ObjectNode project = JSON.createObjectNode();
        ObjectNode selectedWorkspace = project.putArray("workspaces").addObject();
        selectedWorkspace.put("projectRoot", workspace.toString());
        selectedWorkspace.putObject("defaults").putObject("orchestrator")
                .put("resumePollMax", 1)
                .put("resumePollIntervalMs", 10)
                .put("resumePollTimeoutMs", 100);
        selectedWorkspace.putArray("executionProfiles");
        Files.writeString(projectRoot.resolve("projects.json"), project.toPrettyString());
    }

    private void writeProbeRegistry(int probePort) throws Exception {
        ObjectNode registry = JSON.createObjectNode();
        registry.put("defaultProfile", "local");
        ObjectNode probe = registry.putObject("profiles").putObject("local")
                .putObject("probes").putObject("sidecar");
        probe.put("baseUrl", "http://127.0.0.1:" + probePort);
        probe.putArray("include").add(SidecarTargetMain.class.getName());
        probe.putArray("exclude");
        ObjectNode workspaceBinding = registry.putArray("workspaces").addObject();
        workspaceBinding.put("root", workspace.toString());
        workspaceBinding.put("profile", "local");
        Files.writeString(workspace.resolve(".mcpjvm/probe-config.json"), registry.toPrettyString());
    }

    private void selectProfile(String suiteType, String planName) throws Exception {
        Path projectPath = workspace.resolve(".mcpjvm/demo/projects.json");
        ObjectNode project = (ObjectNode) JSON.readTree(Files.readString(projectPath));
        ObjectNode selectedWorkspace = (ObjectNode) project.path("workspaces").get(0);
        selectedWorkspace.putArray("executionProfiles").addObject()
                .put("executionProfile", planName)
                .put("executionPolicy", "stop_on_fail")
                .put("suiteType", suiteType)
                .putArray("plans").addObject().put("order", 1).put("planName", planName);
        Files.writeString(projectPath, project.toPrettyString());
    }

    private void writeRegressionPlan(int port) throws Exception {
        selectProfile("regression", "regression-smoke");
        Path planRoot = planRoot("regression", "regression-smoke");
        Files.writeString(planRoot.resolve("metadata.json"),
                """
                {"specVersion":"1.0.0","suiteType":"regression","execution":{"intent":"regression",
                "probeVerification":false,"pinStrictProbeKey":false,
                "discoveryPolicy":"allow_discoverable_prerequisites"}}
                """);
        Files.writeString(planRoot.resolve("contract.json"), """
                {"targets":[{"type":"class_method","selectors":{"fqcn":"example.StdioController",
                "method":"run","sourceRoot":"src/main/java"}}],"prerequisites":[],
                "steps":[{"order":1,"id":"transport","targetRef":0,"protocol":"http",
                "transport":{"http":{"method":"GET","url":"http://127.0.0.1:%d/transport"}},
                "expect":[{"id":"status","actualPath":"response.statusCode",
                "operator":"field_equals","expected":200}]}]}
                """.formatted(port));
    }

    private void writePerformancePlan(int targetPort, int probePort, String strictLineKey) throws Exception {
        selectProfile("performance", "performance-smoke");
        Path planRoot = planRoot("performance", "performance-smoke");
        Path jmeter = writeJmeterFixture("http://127.0.0.1:" + targetPort + "/workload");
        Files.writeString(planRoot.resolve("metadata.json"),
                "{\"specVersion\":\"1.0.0\",\"suiteType\":\"performance\","
                        + "\"execution\":{\"intent\":\"performance\"}}");
        Files.writeString(planRoot.resolve("contract.json"), """
                {"workloadProvider":{"type":"jmeter","mode":"generated_http",
                "options":{"installationPath":%s}},
                "entrypoints":[{"transport":{"protocol":"http","baseUrl":"http://127.0.0.1:%d",
                "healthCheckPath":"/workload"},"request":{"method":"GET","path":"/workload"}}],
                "loadModel":{"mode":"concurrency","concurrency":1,"rampUpSeconds":0,"durationSeconds":5},
                "observationTargets":{"probeBaseUrl":"http://127.0.0.1:%d",
                "requiredLineHits":[%s]},
                "successCriteria":{"maxErrorRatePct":0,"minThroughputPerSec":0.1,"p95LatencyMs":100}}
                """.formatted(
                        JSON.writeValueAsString(jmeter.toString()),
                        targetPort,
                        probePort,
                        JSON.writeValueAsString(strictLineKey)));
    }

    private void writeSecurityPlan(int port) throws Exception {
        selectProfile("security", "security-smoke");
        Path planRoot = planRoot("security", "security-smoke");
        Files.writeString(planRoot.resolve("metadata.json"), "{\"suiteType\":\"security\"}");
        Files.writeString(planRoot.resolve("contract.json"), """
                {"suiteType":"security","securityMode":"blackbox",
                "targetBoundary":{"environment":"local-ci","baseUrl":"http://127.0.0.1:%d",
                "allowedHosts":["127.0.0.1"],"allowedPorts":[%d],
                "externalNetworkAccess":"forbidden"},
                "entrypoints":[{"id":"transport","transport":{"type":"http","method":"GET",
                "path":"/transport"},"baseline":{}}],
                "authenticationProfiles":[{"id":"anonymous","kind":"anonymous"}],
                "customCases":[{"id":"query-denied","category":"injection",
                "entrypointRef":"transport","authenticationProfileRef":"anonymous",
                "baseline":{"expect":{"outcome":"allow","statusCodes":[200]}},
                "attack":{"query":{"attack":"1"},
                "expect":{"outcome":"deny","statusCodes":[403]}}}],
                "exhaustiveness":{"mode":"finite_matrix","requireAllCases":true,
                "onIncomplete":"blocked"},
                "safetyPolicy":{"maxConcurrency":1,"maxRequestsPerSecond":10,
                "maxDurationMs":10000,"destructivePayloads":"forbidden",
                "stateMutation":"test-tenant-only","cleanupRequired":true},
                "verdictPolicy":{"failOnSeverity":["critical","high"],
                "requireExhaustiveCompletion":true,"blockedCountsAs":"fail"}}
                """.formatted(port, port));
    }

    private Path planRoot(String suiteType, String planName) throws IOException {
        Path path = workspace.resolve(".mcpjvm/demo/plans").resolve(suiteType).resolve(planName);
        Files.createDirectories(path);
        return path;
    }

    private Path writeJmeterFixture(String workloadUrl) throws IOException {
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path installation = workspace.resolve("fixture-jmeter-home");
        Path executable = installation.resolve("bin").resolve(windows ? "jmeter.cmd" : "jmeter");
        Files.createDirectories(executable.getParent());
        Path workloadDriver = workspace.resolve("fixture-workload.cjs");
        Path detachedLauncher = workspace.resolve("fixture-detached-launcher.cjs");
        Path driverPids = workspace.resolve("fixture-workload-pids.txt");
        Files.writeString(workloadDriver, """
                const http = require("node:http");
                const target = process.argv[2];
                let remaining = 400;
                function request() {
                  const call = http.get(target, (response) => {
                    response.resume();
                    response.on("end", () => {
                      remaining -= 1;
                      if (remaining > 0) setTimeout(request, 25);
                    });
                  });
                  call.on("error", () => process.exit(1));
                }
                request();
                """);
        Files.writeString(detachedLauncher, """
                const { spawn } = require("node:child_process");
                const fs = require("node:fs");
                const child = spawn(process.execPath, [process.argv[2], process.argv[3]], {
                  detached: true, stdio: "ignore", windowsHide: true
                });
                fs.appendFileSync(process.argv[4], String(child.pid) + "\\n");
                child.unref();
                """);
        String script = windows
                        ? "@echo off\r\nnode \"" + detachedLauncher + "\" \"" + workloadDriver + "\" \""
                        + workloadUrl + "\" \"" + driverPids + "\"\r\n"
                        + "> \"%5\" echo elapsed,success\r\n>> \"%5\" echo 10,true\r\n"
                        + "> \"%7\" echo fixture-jmeter-ok\r\nexit /b 0\r\n"
                : "#!/bin/sh\nnode \"" + detachedLauncher + "\" \"" + workloadDriver + "\" \""
                        + workloadUrl + "\" \"" + driverPids + "\"\n"
                        + "printf 'elapsed,success\\n10,true\\n' > \"$5\"\n"
                        + "printf 'fixture-jmeter-ok\\n' > \"$7\"\n";
        Files.writeString(executable, script);
        if (!windows) {
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        }
        return installation;
    }

    private void awaitWorkloadDrivers() throws Exception {
        Path pids = workspace.resolve("fixture-workload-pids.txt");
        assertThat(pids).isRegularFile();
        for (String line : Files.readAllLines(pids)) {
            long pid = Long.parseLong(line.trim());
            var process = ProcessHandle.of(pid);
            if (process.isPresent()) {
                process.get().onExit().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private static HttpServer startTarget() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/__probe/status", exchange -> respond(exchange, 200, """
                {"probe":{"key":"example.StdioController#run:7","hitCount":%d,
                "lastHitEpoch":%d,"lineResolvable":true,"lineValidation":"resolvable"}}
                """.formatted(hits.incrementAndGet(), System.currentTimeMillis())));
        server.createContext("/__probe/reset", exchange -> respond(exchange, 200, """
                {"results":[{"key":"example.StdioController#run:7","ok":true,
                "lineResolvable":true,"lineValidation":"resolvable"}]}
                """));
        server.createContext("/transport", exchange -> {
            int status = exchange.getRequestURI().getRawQuery() == null ? 200 : 403;
            respond(exchange, status, "{\"safe\":\"transport-ok\",\"authorization\":\"Bearer should-not-escape\"}");
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void initialize(ServerProcess server) throws Exception {
        server.send(request(1, "initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("roots", Map.of("listChanged", true)),
                "clientInfo", Map.of("name", "mcpjvm-623-parity", "version", "1.0"))));
        assertThat(server.responseFor(1).path("result").path("protocolVersion").asText())
                .isEqualTo(PROTOCOL_VERSION);
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
        server.send(Map.of("jsonrpc", "2.0", "method", "notifications/roots/list_changed", "params", Map.of()));
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static Path jarPath() {
        String configured = System.getProperty("mcpServerJar");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Failsafe system property mcpServerJar is missing");
        }
        return Path.of(configured).toAbsolutePath();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Bounded Java 21 target whose hot method supplies real Strict Line Key evidence. */
    public static final class SidecarTargetMain {
        private static final AtomicLong TICKS = new AtomicLong();

        private SidecarTargetMain() {
        }

        /** Serves the bounded workload; only the actual request executes the probed line after reset. */
        public static void main(String[] args) throws Exception {
            int port = Integer.parseInt(args[0]);
            String strictLineKey = tick(null);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/workload", exchange -> {
                tick(strictLineKey);
                respond(exchange, 200, "{\"workload\":\"ok\"}");
            });
            server.start();
            System.out.println(strictLineKey);
            System.out.flush();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1_000);
                }
            } finally {
                server.stop(0);
            }
        }

        private static String tick(String announcedKey) {
            int line = StackWalker.getInstance().walk(frames -> frames.findFirst().orElseThrow().getLineNumber());
            TICKS.incrementAndGet();
            if (announcedKey == null) {
                String key = SidecarTargetMain.class.getName() + "#tick:" + line;
                System.out.println(key);
                System.out.flush();
                return key;
            }
            return announcedKey;
        }
    }

    private static final class SidecarTargetProcess implements AutoCloseable {
        private final Process process;
        private final String strictLineKey;

        private SidecarTargetProcess(Process process, String strictLineKey) {
            this.process = process;
            this.strictLineKey = strictLineKey;
        }

        static SidecarTargetProcess start(int workloadPort) throws IOException {
            String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "java.exe" : "java";
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"),
                    SidecarTargetMain.class.getName(), Integer.toString(workloadPort))
                    .redirectErrorStream(true)
                    .start();
            String key = process.inputReader(StandardCharsets.UTF_8).readLine();
            if (key == null || key.isBlank()) {
                process.destroyForcibly();
                throw new IOException("Sidecar target did not publish a Strict Line Key");
            }
            return new SidecarTargetProcess(process, key.trim());
        }

        long pid() {
            return process.pid();
        }

        long processStartEpochMs() {
            return process.toHandle().info().startInstant().orElseThrow().toEpochMilli();
        }

        String strictLineKey() {
            return strictLineKey;
        }

        boolean isAlive() {
            return process.isAlive();
        }

        @Override
        public void close() throws Exception {
            process.destroy();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            assertThat(process.isAlive()).isFalse();
        }
    }

    /**
     * Production Spring composition used to invoke the four direct CDE registrations without
     * replacing their substantive Suite action handlers with test doubles.
     */
    private static final class DirectSuiteOperations implements AutoCloseable {
        private final Map<String, OperationRegistration<?, ?>> registrations;

        private DirectSuiteOperations(Map<String, OperationRegistration<?, ?>> registrations) {
            this.registrations = registrations;
        }

        static DirectSuiteOperations create(Path workspace) {
            TransportExecutionFeature transport = transport();
            ProbeFeature probe = probe();
            RegressionPlanPreflight preflight = new RegressionPlanPreflight();
            RegressionSuiteFeature regression = new DefaultRegressionSuiteFeature(List.of(
                    new PreflightRegressionPlanAction(preflight),
                    new ExecuteRegressionPlanAction(new RegressionPlanExecutor(preflight, transport, JSON))));
            JmeterWorkloadExecutor workload = new JmeterWorkloadExecutor(
                    new JmeterJmxRenderer(), new DefaultJmeterProcessRunner(), new JmeterJtlCollector());
            PerformanceSuiteFeature performance = new DefaultPerformanceSuiteFeature(List.of(
                    new ExecutePerformancePlanAction(
                            new PerformancePlanExecutor(
                                    new JmeterExecutableResolver(), workload, probe, transport))));
            SecuritySuiteFeature security = new DefaultSecuritySuiteFeature(List.of(
                    new ExecuteSecurityPlanAction(
                            new SecurityPlanExecutor(transport, new SecurityKnowledgeCatalog(), probe))));
            List<OperationRegistration<?, ?>> values = new ArrayList<>();
            ArtifactManagementSupport support = new ArtifactManagementSupport(
                    () -> Optional.of(workspace), new ArtifactJsonStore(JSON),
                    new SqliteRunStateStore(JSON), JSON);
            ArtifactOperationCatalog artifacts = new ArtifactOperationCatalog(
                    new ProbeConfigOperations(support), new ProjectContextOperations(support),
                    new PlanOperations(support), new RunResultOperations(support),
                    new ExecutionExportOperations(support), new OperationExposure(
                            ArtifactOperationCatalog.TOOL_NAME, "ArtifactManagementMcpTool",
                            Arrays.stream(ArtifactManagementAction.values())
                                    .map(ArtifactManagementAction::routeId).toList()));
            TrustedDirectSuiteRun trusted = new TrustedDirectSuiteRun(artifacts,
                    (project, type, plan, run) -> Optional.of(workspace.resolve(".mcpjvm")
                            .resolve(project).resolve("plans").resolve(type).resolve(plan)
                            .resolve("runs").resolve(run).toString()), JSON);
            values.addAll(TrustedRegressionSuiteRegistrations.create(regression, JSON, trusted));
            values.addAll(TrustedPerformanceSuiteRegistrations.create(performance, JSON, trusted));
            values.addAll(TrustedSecuritySuiteRegistrations.create(security, JSON, trusted));
            Map<String, OperationRegistration<?, ?>> registrations = new java.util.LinkedHashMap<>();
            for (OperationRegistration<?, ?> registration : values) {
                registrations.put(registration.descriptor().operationId().value(), registration);
            }
            return new DirectSuiteOperations(Map.copyOf(registrations));
        }

        private static TransportExecutionFeature transport() {
            HttpTransportSafetyPolicy policy = new HttpTransportSafetyPolicy(java.util.Set.of());
            HttpSensitiveDataRedactor redactor = new HttpSensitiveDataRedactor();
            HttpTransportProvider http = new HttpTransportProvider(
                    new HttpRequestValidator(policy, redactor, JSON),
                    new HttpRedirectResponseExecutor(
                            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                            policy,
                            redactor));
            return new DefaultTransportExecutionFeature(List.of(
                    new ExecuteTransportAction(
                            () -> false,
                            new TransportProviderRegistry(List.of(
                                    http,
                                    new GrpcTransportProvider(),
                                    new KafkaTransportProvider(),
                                    new CustomTransportProvider())))));
        }

        private static ProbeFeature probe() {
            ProbeEndpointConfiguration endpoint = new ProbeEndpointConfiguration(
                    null,
                    new ProbeEndpointPaths(
                            "/__probe/status",
                            "/__probe/reset",
                            "/__probe/actuate",
                            "/__probe/capture",
                            "/__probe/profiler"),
                    new ProbeRequestPolicy(
                            Duration.ofSeconds(15),
                            Duration.ofMillis(100),
                            1,
                            false,
                            3,
                            new ProbeRequestBounds(
                                    Duration.ofSeconds(1),
                                    Duration.ofSeconds(60),
                                    Duration.ofMillis(100),
                                    Duration.ofSeconds(5),
                                    1,
                                    10)),
                    new ProbeEndpointLimits(64, 128, 4_096, 65_536, 1_048_576));
            ProbeResponseCompactionPolicy compaction = new ProbeResponseCompactionPolicy(
                    false, 256, 32, 64, 256, java.util.Set.of("content-type", "content-length"));
            var client = new HttpProbeEndpointClient();
            ProbeRegistryProvider registry = () -> null;
            ProbeTargetResolver resolver = ProbeTargetResolver.dynamic(endpoint, registry);
            ProbeStatusAction status = new ProbeStatusAction(resolver, endpoint, client, compaction);
            return new DefaultProbeFeature(List.of(
                    new ProbeCheckAction(resolver, endpoint, client, compaction),
                    status,
                    new ProbeResetAction(resolver, endpoint, client, compaction),
                    new ProbeWaitForHitAction(status, endpoint.requestPolicy(), Clock.systemUTC(), Thread::sleep),
                    new ProbeCaptureAction(resolver, endpoint, client, compaction),
                    new ProbeActuateAction(resolver, endpoint, client, compaction),
                    new ProbeProfilerAction(
                            resolver,
                            endpoint,
                            client,
                            compaction,
                            new LocalProbeProfilerOutputStore())));
        }

        JsonNode execute(String operationId, JsonNode input) {
            OperationRegistration<?, ?> registration = registrations.get(operationId);
            if (registration == null) {
                throw new IllegalArgumentException("Unknown direct Suite operation: " + operationId);
            }
            OperationId id = registration.descriptor().operationId();
            var builtIn = OperationManifestLoader.loadBuiltIn();
            OperationDirectory directory = new OperationDirectory(List.of(registration),
                    new OperationManifestDocument(builtIn.version(), Map.of(id, builtIn.documentation(id))), JSON);
            var result = directory.execute(new OperationInvocation(id, input, true));
            assertThat(result.status()).as(result.toString()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
            return result.result();
        }

        @Override
        public void close() {
            // Core owners hold no background resources between invocations.
        }
    }

    private static final class ServerProcess implements AutoCloseable {
        private final Process process;
        private final OutputStream stdin;
        private final String workspaceUri;
        private final BlockingQueue<String> responses = new LinkedBlockingQueue<>();
        private final List<String> stdout = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder stderr = new StringBuilder();
        private final ExecutorService readers = Executors.newFixedThreadPool(2);

        private ServerProcess(Process process, Path workspace) {
            this.process = process;
            stdin = process.getOutputStream();
            workspaceUri = workspace.toUri().toString();
            readers.submit(() -> collect(process.getInputStream(), true));
            readers.submit(() -> collect(process.getErrorStream(), false));
        }

        static ServerProcess start(Path jar, Path workspace, String workloadUrl) throws IOException {
            String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "java.exe" : "java";
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-jar", jar.toString(), "--workspace-root=" + workspace);
            builder.environment().put("MCPJVM_TEST_WORKLOAD_URL", workloadUrl);
            Process process = builder.start();
            return new ServerProcess(process, workspace);
        }

        JsonNode call(int id, String tool, Map<String, Object> arguments) throws Exception {
            send(request(id, "tools/call", Map.of("name", tool, "arguments", arguments)));
            JsonNode response = responseFor(id);
            assertThat(response.path("result").path("isError").asBoolean())
                    .as(response.toString()).isFalse();
            return JSON.readTree(response.path("result").path("content").get(0).path("text").asText());
        }

        void send(Map<String, Object> message) throws IOException {
            stdin.write(JSON.writeValueAsBytes(message));
            stdin.write('\n');
            stdin.flush();
        }

        JsonNode responseFor(int id) throws Exception {
            Instant deadline = Instant.now().plus(TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                String line = responses.poll(100, TimeUnit.MILLISECONDS);
                if (line == null) {
                    continue;
                }
                JsonNode message = JSON.readTree(line);
                if ("roots/list".equals(message.path("method").asText())) {
                    respondToRoots(message);
                } else if (message.path("id").asInt(-1) == id) {
                    return message;
                }
            }
            throw new AssertionError("Timed out waiting for response " + id + ". stderr=" + stderr);
        }

        private void respondToRoots(JsonNode request) throws IOException {
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", request.path("id"));
            response.putObject("result").putArray("roots").addObject()
                    .put("uri", workspaceUri).put("name", "mcpjvm-623-workspace");
            stdin.write(JSON.writeValueAsBytes(response));
            stdin.write('\n');
            stdin.flush();
        }

        private void collect(InputStream stream, boolean protocol) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (protocol && !line.isBlank()) {
                        stdout.add(line);
                        responses.offer(line);
                    } else if (!protocol) {
                        synchronized (stderr) {
                            stderr.append(line).append('\n');
                        }
                    }
                }
            } catch (IOException exception) {
                synchronized (stderr) {
                    stderr.append(exception.getMessage()).append('\n');
                }
            }
        }

        @Override
        public void close() throws Exception {
            stdin.close();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                fail("MCP process did not stop after stdin closed");
            }
            readers.shutdown();
            assertThat(readers.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(process.exitValue()).as(stderr.toString()).isZero();
            assertThat(stdout).isNotEmpty().allSatisfy(line -> {
                try {
                    assertThat(JSON.readTree(line).path("jsonrpc").asText()).isEqualTo("2.0");
                } catch (IOException exception) {
                    throw new AssertionError("stdout contained a non-JSON-RPC line", exception);
                }
            });
        }
    }
}
