package com.nimbly.mcpjavadevtools.server;

import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.LOOPBACK_HTTP_PREFIX;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.PERFORMANCE_PLAN_TEXT;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.PROBE_REGRESSION_PLAN_TEXT;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.PROTOCOL_VERSION;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.USER_CONTROLLER;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.USER_FAILURE_TRACE;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.USER_PROFILE_LINE_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServerJvmCapabilitiesIT extends McpServerStdioSupport {

    @Test
    void executableJarUsesCdeJvmLifecycleAgainstSocialPlatformUserApp() throws Exception {
        Path fixtureJar = repositoryRoot().resolve(
                "test/fixtures/spring-apps/social-platform/user-service/user-app/target/"
                        + "user-app-0.1.0-SNAPSHOT.jar");
        assertThat(fixtureJar)
                .as("the Maven pre-integration-test phase must package the user-app fixture")
                .isRegularFile();
        Path serverJar = jarPath();
        Path workspace = JavaItScenario.workspace();
        Path routeProject = copyRouteFixtureSource(workspace);
        List<Map<String, Object>> transcript = new ArrayList<>();
        try {
            try (SpringFixtureTargetProcess target = SpringFixtureTargetProcess.start(fixtureJar);
                    McpServerProcess server = McpServerProcess.start(
                            serverJar, workspace, "--workspace-root=" + workspace)) {
            target.awaitHealthy();

            Map<String, Object> initialize = initializeRequest();
            server.send(initialize);
            JsonNode initializeResponse = server.responseFor(1);
            assertThat(initializeResponse.path("result").path("protocolVersion").asText())
                    .isEqualTo(PROTOCOL_VERSION);
            transcript.add(Map.of("request", initialize, "response", initializeResponse));
            Map<String, Object> initialized = Map.of(
                    "jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of());
            server.send(initialized);

            Map<String, Object> toolsListRequest = request(2, "tools/list", Map.of());
            server.send(toolsListRequest);
            JsonNode toolsListResponse = server.responseFor(2);
            JsonNode tools = toolsListResponse.path("result").path("tools");
            List<String> toolNames = new ArrayList<>();
            for (JsonNode tool : tools) {
                toolNames.add(tool.path("name").asText());
                assertThat(tool.has("outputSchema")).isTrue();
            }
            assertThat(toolNames).containsExactlyInAnyOrder(
                    "operation_catalog", "operation_describe", "operation_execute");
            JsonNode catalogTool = findTool(tools, "operation_catalog");
            JsonNode describeTool = findTool(tools, "operation_describe");
            JsonNode executeTool = findTool(tools, "operation_execute");
            assertThat(catalogTool.path("inputSchema").path("properties").has("query")).isTrue();
            assertThat(catalogTool.path("inputSchema").path("properties").has("limit")).isTrue();
            assertThat(catalogTool.path("inputSchema").path("properties").has("input")).isFalse();
            assertThat(describeTool.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(describeTool.path("inputSchema").path("properties").has("input")).isFalse();
            assertThat(executeTool.path("inputSchema").path("properties").has("operationId")).isTrue();
            assertThat(executeTool.path("inputSchema").path("properties").has("arguments")).isTrue();
            assertThat(executeTool.path("inputSchema").path("properties").has("confirmed")).isTrue();
            assertThat(executeTool.path("inputSchema").path("properties").has("input")).isFalse();
            transcript.add(Map.of("request", toolsListRequest, "response", toolsListResponse));

            Map<String, Object> catalogRequest = request(3, "tools/call", Map.of(
                    "name", "operation_catalog",
                    "arguments", Map.of("query", "jvm_lifecycle")));
            server.send(catalogRequest);
            JsonNode catalogResponse = server.responseFor(3);
            JsonNode catalog = toolPayload(catalogResponse);
            assertThat(catalog.path("status").asText()).isEqualTo("succeeded");
            List<String> operationIds = new ArrayList<>();
            for (JsonNode entry : catalog.path("entries")) {
                operationIds.add(entry.path("operationId").asText());
            }
            assertThat(operationIds).containsExactlyInAnyOrder(
                    "jvm_lifecycle.list_jvms", "jvm_lifecycle.attach", "jvm_lifecycle.deactivate");
            transcript.add(Map.of("request", catalogRequest, "response", catalogResponse));

            Map<String, Object> zeroLimitCatalogRequest = request(13, "tools/call", Map.of(
                    "name", "operation_catalog",
                    "arguments", Map.of("limit", 0)));
            server.send(zeroLimitCatalogRequest);
            JsonNode zeroLimitCatalogResponse = server.responseFor(13);
            JsonNode zeroLimitCatalog = toolPayload(zeroLimitCatalogResponse);
            assertThat(zeroLimitCatalog.path("status").asText()).isEqualTo("invalid_input");
            assertThat(zeroLimitCatalog.path("reasonCode").asText()).isEqualTo("catalog_limit_invalid");
            transcript.add(Map.of("request", zeroLimitCatalogRequest, "response", zeroLimitCatalogResponse));

            Map<String, Object> describeRequest = request(4, "tools/call", Map.of(
                    "name", "operation_describe",
                    "arguments", Map.of("operationId", "jvm_lifecycle.attach")));
            server.send(describeRequest);
            JsonNode describeResponse = server.responseFor(4);
            JsonNode description = toolPayload(describeResponse);
            JsonNode details = description.path("operation");
            assertThat(description.path("status").asText()).isEqualTo("succeeded");
            assertThat(details.path("safety").path("confirmationRequired").asBoolean()).isTrue();
            assertThat(details.path("inputSchema").path("properties").has("confirm")).isFalse();
            List<String> documentedArguments = new ArrayList<>();
            for (JsonNode argument : details.path("arguments")) {
                documentedArguments.add(argument.path("name").asText());
            }
            assertThat(documentedArguments).contains("pid", "expectedProcessStartEpochMs")
                    .doesNotContain("confirm");
            assertThat(description.toString().toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("deprecated", "replacement", "alias");
            transcript.add(Map.of("request", describeRequest, "response", describeResponse));

            Map<String, Object> listRequest = request(5, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of("operationId", "jvm_lifecycle.list_jvms", "arguments", Map.of())));
            server.send(listRequest);
            JsonNode listResponse = server.responseFor(5);
            JsonNode discovery = toolPayload(listResponse);
            assertThat(discovery.path("status").asText()).isEqualTo("succeeded");
            JsonNode candidates = discovery.path("result").path("actionResult").path("jvms");
            JsonNode selected = null;
            for (JsonNode candidate : candidates) {
                if (Long.toString(target.pid()).equals(candidate.path("pid").asText())) {
                    selected = candidate;
                    break;
                }
            }
            assertThat(candidates.isArray()).isTrue();
            assertThat(selected)
                    .as("list_jvms must return the fixture candidate")
                    .isNotNull();
            boolean fixtureDiscovered = true;
            String selectedPid = selected.path("pid").asText();
            long selectedProcessStartEpochMs = selected.path("processStartEpochMs").asLong();
            assertThat(selectedPid).isEqualTo(Long.toString(target.pid()));
            assertThat(selectedProcessStartEpochMs).isEqualTo(target.processStartEpochMs());
            transcript.add(Map.of("request", listRequest, "response", listResponse));

            int probePort = freePort();
            Map<String, Object> attachArguments = Map.of(
                    "pid", selectedPid,
                    "expectedProcessStartEpochMs", selectedProcessStartEpochMs,
                    "probeHost", "127.0.0.1",
                    "probePort", probePort);
            Map<String, Object> confirmationRequest = request(6, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.attach",
                            "arguments", attachArguments,
                            "confirmed", false)));
            server.send(confirmationRequest);
            JsonNode confirmationResponse = server.responseFor(6);
            JsonNode confirmation = toolPayload(confirmationResponse);
            assertThat(confirmation.path("status").asText()).isEqualTo("confirmation_required");
            assertThat(target.awaitSidecarReachability(probePort, false, Duration.ofSeconds(1))).isTrue();
            transcript.add(Map.of("request", confirmationRequest, "response", confirmationResponse));

            Map<String, Object> attachRequest = request(7, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.attach",
                            "arguments", attachArguments,
                            "confirmed", true)));
            server.send(attachRequest);
            JsonNode attachResponse = server.responseFor(7, Duration.ofSeconds(90));
            JsonNode attach = toolPayload(attachResponse);
            assertThat(attach.path("status").asText()).isEqualTo("succeeded");
            assertThat(attach.path("result").path("reasonCode").asText()).isEqualTo("active");
            assertThat(attach.path("result").path("actionResult").path("outcome").asText())
                    .isEqualTo("active");
            assertThat(target.awaitSidecarReachability(probePort, true, Duration.ofSeconds(15))).isTrue();
            assertThat(target.isAlive()).isTrue();
            transcript.add(Map.of("request", attachRequest, "response", attachResponse));

            String probeBaseUrl = LOOPBACK_HTTP_PREFIX + probePort;
            server.send(executeRequest(14, "probe.check", Map.of(
                    "baseUrl", probeBaseUrl,
                    "timeoutMs", 5_000), false));
            JsonNode probeCheck = toolPayload(server.responseFor(14));
            assertThat(probeCheck.path("status").asText()).isEqualTo("succeeded");
            assertThat(probeCheck.path("result").path("status").asText()).isEqualTo("SUCCESS");
            assertThat(probeCheck.path("result").path("actionResult").path("status")
                    .path("httpStatus").asInt()).isEqualTo(200);

            server.send(executeRequest(15, "route_synthesis.class_methods", Map.of(
                    "projectRootAbs", routeProject.toString(),
                    "classHint", USER_CONTROLLER,
                    "probeBaseUrl", probeBaseUrl), false));
            JsonNode routeSynthesis = toolPayload(server.responseFor(15, Duration.ofSeconds(30)));
            assertThat(routeSynthesis.path("status").asText()).isEqualTo("succeeded");
            assertThat(routeSynthesis.path("result").path("status").asText())
                    .as(routeSynthesis.toPrettyString())
                    .isEqualTo("ok");
            assertThat(routeSynthesis.path("result").path("actionResult").isObject()).isTrue();

            server.send(executeRequest(16, "failure_analysis.analyze_trace", Map.of(
                    "trace", USER_FAILURE_TRACE,
                    "sidecarBaseUrl", probeBaseUrl,
                    "timeoutMs", 5_000), false));
            JsonNode failureAnalysis = toolPayload(server.responseFor(16, Duration.ofSeconds(30)));
            assertThat(failureAnalysis.path("status").asText()).isEqualTo("succeeded");
            assertThat(failureAnalysis.path("result").path("outcome").asText())
                    .as(failureAnalysis.toPrettyString())
                    .isIn("ANALYZED", "INCONCLUSIVE");
            assertThat(failureAnalysis.path("result").path("reasonCode").asText())
                    .isNotEqualTo("sidecar_failure_analysis_unavailable");

            String performanceProject = "fixture-644-performance";
            String performancePlan = "live";
            String regressionPlan = "probe-live";
            String performanceRun = "stdio-644-performance-" + System.currentTimeMillis();
            Map<String, Object> performanceProjectPayload = Map.of(
                    "workspaces", List.of(Map.of(
                            "projectRoot", workspace.toString(),
                            "defaults", Map.of("orchestrator", Map.of(
                                    "resumePollMax", 1,
                                    "resumePollIntervalMs", 10,
                                    "resumePollTimeoutMs", 100)),
                            "executionProfiles", List.of(
                                    Map.of(
                                            "executionProfile", "performance-smoke",
                                            "executionPolicy", "stop_on_fail",
                                            "suiteType", "performance",
                                            "plans", List.of(Map.of(
                                                    "order", 1,
                                                    "planName", performancePlan,
                                                    "onFail", "inherit"))),
                                    Map.of(
                                            "executionProfile", "regression-probe-smoke",
                                            "executionPolicy", "stop_on_fail",
                                            "suiteType", "regression",
                                            "plans", List.of(Map.of(
                                                    "order", 1,
                                                    "planName", regressionPlan,
                                                    "onFail", "inherit")))))));
            server.send(executeRequest(17, "artifact_management.project_context.upsert", Map.of(
                    "projectName", performanceProject,
                    "payload", performanceProjectPayload), true));
            JsonNode performanceProjectUpsert = toolPayload(server.responseFor(17));
            assertThat(performanceProjectUpsert.path("status").asText()).isEqualTo("succeeded");
            assertThat(performanceProjectUpsert.path("result").path("status").asText())
                    .as(performanceProjectUpsert.toPrettyString())
                    .isEqualTo("ok");

            Map<String, Object> performancePlanPayload = Map.of(
                    "metadata", Map.of(
                            "specVersion", "1.0.0",
                            "suiteType", "performance",
                            "execution", Map.of("intent", "performance")),
                    "contract", performanceContract(target.port(), probeBaseUrl),
                    "plan", PERFORMANCE_PLAN_TEXT);
            server.send(executeRequest(18, "artifact_management.performance_plan.upsert", Map.of(
                    "projectName", performanceProject,
                    "planName", performancePlan,
                    "payload", performancePlanPayload), true));
            JsonNode performancePlanUpsert = toolPayload(server.responseFor(18));
            assertThat(performancePlanUpsert.path("status").asText()).isEqualTo("succeeded");
            assertThat(performancePlanUpsert.path("result").path("status").asText())
                    .as(performancePlanUpsert.toPrettyString())
                    .isEqualTo("ok");

            server.send(executeRequest(19, "performance_suite.execute_plan", Map.of(
                    "projectName", performanceProject,
                    "executionProfile", "performance-smoke",
                    "planName", performancePlan,
                    "suiteRunId", performanceRun), true));
            JsonNode performance = toolPayload(server.responseFor(19, Duration.ofSeconds(60)));
            assertThat(performance.path("status").asText()).isEqualTo("succeeded");
            assertThat(performance.path("result").path("status").asText())
                    .as(performance.toPrettyString())
                    .isEqualTo("completed");
            assertThat(performance.path("result").path("details").path("runStatus").asText())
                    .isEqualTo("pass");

            Map<String, Object> regressionPlanPayload = Map.of(
                    "metadata", Map.of(
                            "specVersion", "1.0.0",
                            "suiteType", "regression",
                            "execution", Map.of(
                                    "intent", "regression",
                                    "probeVerification", true,
                                    "pinStrictProbeKey", false)),
                    "contract", Map.of(
                            "targets", List.of(Map.of(
                                    "id", "user-app",
                                    "selectors", Map.of(
                                            "fqcn", USER_CONTROLLER,
                                            "method", "getUserProfile",
                                            "sourceRoot", "route-fixture"),
                                    "runtimeVerification", Map.of(
                                            "probeBaseUrl", probeBaseUrl))),
                            "steps", List.of(Map.of(
                                    "order", 1,
                                    "id", "get_user_profile",
                                    "protocol", "http",
                                    "transport", Map.of("http", Map.of(
                                            "method", "GET",
                                            "url", LOOPBACK_HTTP_PREFIX + target.port()
                                                    + "/api/v1/users/alice")),
                                    "expect", List.of(Map.of(
                                            "id", "profile_status",
                                            "actualPath", "response.statusCode",
                                            "operator", "field_equals",
                                            "expected", 200))))),
                    "plan", PROBE_REGRESSION_PLAN_TEXT);
            server.send(executeRequest(32, "artifact_management.regression_plan.upsert", Map.of(
                    "projectName", performanceProject,
                    "planName", regressionPlan,
                    "payload", regressionPlanPayload), true));
            JsonNode regressionPlanUpsert = toolPayload(server.responseFor(32));
            assertThat(regressionPlanUpsert.path("status").asText()).isEqualTo("succeeded");
            assertThat(regressionPlanUpsert.path("result").path("status").asText())
                    .as(regressionPlanUpsert.toPrettyString())
                    .isEqualTo("ok");

            server.send(executeRequest(33, "regression_suite.execute_plan", Map.of(
                    "projectName", performanceProject,
                    "executionProfile", "regression-probe-smoke",
                    "planName", regressionPlan,
                    "suiteRunId", "stdio-644-regression-probe-" + System.currentTimeMillis()), true));
            JsonNode regressionProbe = toolPayload(server.responseFor(33, Duration.ofSeconds(60)));
            assertThat(regressionProbe.path("status").asText()).isEqualTo("succeeded");
            assertThat(regressionProbe.path("result").path("status").asText())
                    .as(regressionProbe.toPrettyString())
                    .isEqualTo("ready");
            assertThat(regressionProbe.path("result").path("details").path("runStatus").asText())
                    .isEqualTo("pass");
            assertThat(regressionProbe.path("result").path("details").path("probeVerification")
                    .path("status").asText()).isEqualTo("pass");
            assertThat(regressionProbe.path("result").path("details").path("probeVerification")
                    .path("lineHits").get(0).path("lineHit").asBoolean()).isTrue();
            assertThat(regressionProbe.path("result").path("details").path("probeVerification")
                    .path("lineHits").get(0).path("strictLineKey").asText())
                    .isEqualTo(USER_PROFILE_LINE_KEY);

            Map<String, Object> deactivateRequest = request(8, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.deactivate",
                            "arguments", Map.of(
                                    "pid", selectedPid,
                                    "expectedProcessStartEpochMs", selectedProcessStartEpochMs),
                            "confirmed", true)));
            server.send(deactivateRequest);
            JsonNode deactivateResponse = server.responseFor(8, Duration.ofSeconds(90));
            JsonNode deactivate = toolPayload(deactivateResponse);
            assertThat(deactivate.path("status").asText()).isEqualTo("succeeded");
            assertThat(deactivate.path("result").path("reasonCode").asText()).isEqualTo("deactivated");
            assertThat(deactivate.path("result").path("actionResult").path("outcome").asText())
                    .isEqualTo("deactivated");
            assertThat(target.awaitSidecarReachability(probePort, false, Duration.ofSeconds(15))).isTrue();
            assertThat(target.isHealthy()).isTrue();
            transcript.add(Map.of("request", deactivateRequest, "response", deactivateResponse));

            Map<String, Object> invalidTargetRequest = request(9, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of(
                            "operationId", "jvm_lifecycle.attach",
                            "arguments", Map.of(
                                    "pid", "not-a-pid", "expectedProcessStartEpochMs", 1),
                            "confirmed", true)));
            server.send(invalidTargetRequest);
            JsonNode invalidTargetResponse = server.responseFor(9);
            JsonNode invalidTarget = toolPayload(invalidTargetResponse);
            assertThat(invalidTarget.path("status").asText()).isEqualTo("invalid_input");
            assertThat(invalidTarget.toString()).doesNotContain(repositoryRoot().toString());
            transcript.add(Map.of("request", invalidTargetRequest, "response", invalidTargetResponse));

            Map<String, Object> unknownOperationRequest = request(10, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of("operationId", "unknown.operation", "arguments", Map.of())));
            server.send(unknownOperationRequest);
            JsonNode unknownOperationResponse = server.responseFor(10);
            JsonNode unknownOperation = toolPayload(unknownOperationResponse);
            assertThat(unknownOperation.path("status").asText()).isEqualTo("unsupported_operation");
            assertThat(unknownOperation.toString()).doesNotContain(repositoryRoot().toString());
            transcript.add(Map.of("request", unknownOperationRequest, "response", unknownOperationResponse));

            Map<String, Object> malformedOperationRequest = request(11, "tools/call", Map.of(
                    "name", "operation_execute",
                    "arguments", Map.of("operationId", "invalid id", "arguments", Map.of())));
            server.send(malformedOperationRequest);
            JsonNode malformedOperationResponse = server.responseFor(11);
            JsonNode malformedOperation = toolPayload(malformedOperationResponse);
            assertThat(malformedOperation.path("status").asText()).isEqualTo("unsupported_operation");
            assertThat(malformedOperation.path("operationId").isNull()).isTrue();
            assertThat(malformedOperation.path("result").isNull()).isTrue();
            transcript.add(Map.of("request", malformedOperationRequest, "response", malformedOperationResponse));

            Map<String, Object> unknownDescriptionRequest = request(12, "tools/call", Map.of(
                    "name", "operation_describe",
                    "arguments", Map.of("operationId", "invalid id")));
            server.send(unknownDescriptionRequest);
            JsonNode unknownDescriptionResponse = server.responseFor(12);
            JsonNode unknownDescription = toolPayload(unknownDescriptionResponse);
            assertThat(unknownDescription.path("status").asText()).isEqualTo("unsupported");
            assertThat(unknownDescription.path("operation").isNull()).isTrue();
            transcript.add(Map.of("request", unknownDescriptionRequest, "response", unknownDescriptionResponse));

            server.closeInputAndAwaitTermination();
            assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
            assertThat(server.exitCode()).isZero();

            Path report = serverJar.getParent().resolve("mcpjvm-633-stdio-acceptance.json");
            JSON.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.ofEntries(
                    Map.entry("javaVersion", System.getProperty("java.version")),
                    Map.entry("serverCommand", List.of(
                            McpServerProcess.javaBinary(), "-jar", serverJar.toString())),
                    Map.entry("fixtureCommand", List.of(
                            McpServerProcess.javaBinary(), "-jar", fixtureJar.toString(),
                            "--server.port=" + target.port())),
                    Map.entry("tools", toolNames),
                    Map.entry("mcpTranscript", transcript),
                    Map.entry("fixtureDiscoveredByListJvms", fixtureDiscovered),
                    Map.entry("acceptanceGap", fixtureDiscovered ? "none"
                            : "The list_jvms response contained no candidate for the running fixture."),
                    Map.entry("sidecar", Map.of("host", "127.0.0.1", "port", probePort,
                            "reachableAfterAttach", true, "reachableAfterDeactivate", false)),
                    Map.entry("fixtureHealthAfterDeactivate", true),
                    Map.entry("stdoutContainsOnlyJsonRpc", true),
                    Map.entry("serverExitCode", server.exitCode())));
            assertThat(fixtureDiscovered)
                    .as("list_jvms must return the fixture candidate; see %s", report)
                    .isTrue();
            }
        } finally {
            deleteRecursively(workspace);
        }
    }

}
