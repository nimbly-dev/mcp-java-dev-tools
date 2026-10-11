package com.nimbly.mcpjavadevtools.server;

import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.LOOPBACK_HTTP_PREFIX;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.REGRESSION_PLAN_TEXT;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.SECURITY_PLAN_TEXT;
import static com.nimbly.mcpjavadevtools.server.McpServerAcceptanceConstants.SQL_NULL_AND_QUOTED_PARAMETER_QUERY;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpServerWorkflowIT extends McpServerStdioSupport {

    @Test
    void executableJarPersistsArtifactsAndExecutesTransportAndRegressionSuite() throws Exception {
        String externalHealthUrl = System.getProperty("mcpjvm.acceptance.targetUrl", "").trim();
        Path fixtureJar = repositoryRoot().resolve(
                "test/fixtures/spring-apps/social-platform/user-service/user-app/target/"
                        + "user-app-0.1.0-SNAPSHOT.jar");
        assertThat(fixtureJar)
                .as("the Maven pre-integration-test phase must package the user-app fixture")
                .isRegularFile();
        Path workspace = Files.createTempDirectory(testClassesPath(), "mcpjvm-644-workspace-");
        String projectName = "fixture-644";
        String planName = "health";
        String suiteRunId = "stdio-644-" + System.currentTimeMillis();
        String authorization = "Bearer mcpjvm-644-redaction-sentinel";
        Path verificationDatabase = workspace.resolve("verification.db");
        String verificationJdbcUrl = "jdbc:sqlite:" + verificationDatabase;
        SpringFixtureTargetProcess target = null;

        try {
            try (Connection connection = DriverManager.getConnection(verificationJdbcUrl);
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE fixture_health (id INTEGER PRIMARY KEY, status TEXT NOT NULL)");
                statement.execute("INSERT INTO fixture_health (id, status) VALUES (1, 'ready')");
            }
            Files.writeString(workspace.resolve("acceptance.env"),
                    "MCPJVM_ACCEPTANCE_SQL_KIND=sqlite\n"
                            + "MCPJVM_ACCEPTANCE_SQLITE_FILE=" + verificationDatabase + "\n");
            String healthUrl = externalHealthUrl;
            if (healthUrl.isEmpty()) {
                target = SpringFixtureTargetProcess.start(fixtureJar);
                target.awaitHealthy();
                healthUrl = LOOPBACK_HTTP_PREFIX + target.port() + "/actuator/health";
            }
            URI healthUri = URI.create(healthUrl);
            int targetPort = healthUri.getPort() >= 0
                    ? healthUri.getPort()
                    : ("https".equalsIgnoreCase(healthUri.getScheme()) ? 443 : 80);
            String targetBaseUrl = healthUri.getScheme() + "://" + healthUri.getAuthority();
            try (McpServerProcess server = McpServerProcess.start(
                        jarPath(), workspace, "--workspace-root=" + workspace)) {
                initialize(server);

            Map<String, Object> projectPayload = Map.of(
                    "workspaces", List.of(Map.of(
                            "projectRoot", workspace.toString(),
                            "envFile", "acceptance.env",
                            "variables", Map.of("contextBindings", Map.of(
                                    "sql.connection.verificationDb.kind", "MCPJVM_ACCEPTANCE_SQL_KIND",
                                    "sql.connection.verificationDb.sqlite.filePath",
                                    "MCPJVM_ACCEPTANCE_SQLITE_FILE")),
                            "defaults", Map.of("orchestrator", Map.of(
                                    "resumePollMax", 1,
                                    "resumePollIntervalMs", 10,
                                    "resumePollTimeoutMs", 100)),
                            "executionProfiles", List.of(
                                    Map.of(
                                            "executionProfile", "smoke",
                                            "executionPolicy", "stop_on_fail",
                                            "suiteType", "regression",
                                            "providedContext", Map.of("verificationRowId", 1),
                                            "plans", List.of(Map.of(
                                                    "order", 1,
                                                    "planName", planName,
                                                    "onFail", "inherit"))),
                                    Map.of(
                                            "executionProfile", "security-smoke",
                                            "executionPolicy", "stop_on_fail",
                                            "suiteType", "security",
                                            "plans", List.of(Map.of(
                                                    "order", 1,
                                                    "planName", "safe",
                                                    "onFail", "inherit")))))));
            server.send(executeRequest(20, "artifact_management.project_context.upsert", Map.of(
                    "projectName", projectName,
                    "payload", projectPayload), true));
            JsonNode projectUpsert = toolPayload(server.responseFor(20));
            assertThat(projectUpsert.path("status").asText()).isEqualTo("succeeded");
            assertThat(projectUpsert.path("result").path("status").asText())
                    .as(projectUpsert.toPrettyString())
                    .isEqualTo("ok");
            assertThat(workspace.resolve(".mcpjvm").resolve(projectName).resolve("projects.json"))
                    .isRegularFile();

            Map<String, Object> planPayload = Map.of(
                    "metadata", Map.of("execution", Map.of("intent", "regression")),
                    "contract", Map.of(
                            "targets", List.of(Map.of("id", "fixture")),
                            "steps", List.of(Map.of(
                                    "order", 1,
                                    "id", "health",
                                    "protocol", "http",
                                    "transport", Map.of("http", Map.of(
                                            "method", "GET",
                                            "url", healthUrl)),
                                    "expect", List.of(Map.of(
                                            "id", "health_status",
                                            "actualPath", "response.statusCode",
                                            "operator", "field_equals",
                                            "expected", 200)))),
                            "watchers", List.of(Map.of(
                                    "id", "health_remains_ready",
                                    "dependency", Map.of("stepOrder", 1),
                                    "provider", Map.of(
                                            "type", "http",
                                            "transport", Map.of("request", Map.of(
                                                    "method", "GET",
                                                    "url", healthUrl))),
                                    "waitPolicy", Map.of("timeoutMs", 2_000, "retryMax", 2),
                                    "expect", List.of(Map.of(
                                            "id", "watcher_health_status",
                                            "actualPath", "response.statusCode",
                                            "operator", "field_equals",
                                            "expected", 200)))),
                            "externalVerification", List.of(
                                    Map.of(
                                            "id", "verify_health_externally",
                                            "provider", Map.of("type", "http"),
                                            "request", Map.of("http", Map.of(
                                                    "method", "GET",
                                                    "url", healthUrl)),
                                            "expect", List.of(Map.of(
                                                    "id", "external_health_status",
                                                    "actualPath", "response.statusCode",
                                                    "operator", "field_equals",
                                                    "expected", 200))),
                                    Map.of(
                                            "id", "verify_health_in_database",
                                            "provider", Map.of("type", "sql"),
                                            "request", Map.of("sql", Map.of(
                                                    "connectionRef", "verificationDb",
                                                    "statement", SQL_NULL_AND_QUOTED_PARAMETER_QUERY,
                                                    "parameters", List.of(
                                                            Map.of("name", "nullable", "value", JSON.nullNode()),
                                                            Map.of("name", "id",
                                                                    "valueFromContext", "verificationRowId")))),
                                            "expect", List.of(Map.of(
                                                    "id", "database_health_status",
                                                    "actualPath", "sql.rows[0].status",
                                                    "operator", "field_equals",
                                                    "expected", "ready"))))),
                    "plan", REGRESSION_PLAN_TEXT);
            server.send(executeRequest(21, "artifact_management.regression_plan.upsert", Map.of(
                    "projectName", projectName,
                    "planName", planName,
                    "payload", planPayload), true));
            JsonNode planUpsert = toolPayload(server.responseFor(21));
            assertThat(planUpsert.path("status").asText()).isEqualTo("succeeded");
            assertThat(workspace.resolve(".mcpjvm").resolve(projectName)
                    .resolve("plans/regression").resolve(planName).resolve("contract.json"))
                    .isRegularFile();

            Map<String, Object> securityPlanPayload = Map.of(
                    "metadata", Map.of(
                            "specVersion", "1.0.0",
                            "suiteType", "security",
                            "execution", Map.of("intent", "security")),
                    "contract", Map.of(
                            "suiteType", "security",
                            "securityMode", "blackbox",
                            "targetBoundary", Map.of(
                                    "environment", "local-ci",
                                    "baseUrl", targetBaseUrl,
                                    "allowedHosts", List.of(healthUri.getHost()),
                                    "allowedPorts", List.of(targetPort)),
                            "entrypoints", List.of(Map.of(
                                    "id", "fixture-health",
                                    "transport", Map.of(
                                            "type", "http",
                                            "method", "GET",
                                            "path", healthUri.getPath()))),
                            "authenticationProfiles", List.of(Map.of(
                                    "id", "anonymous",
                                    "kind", "anonymous"))),
                    "plan", SECURITY_PLAN_TEXT);
            server.send(executeRequest(28, "artifact_management.security_plan.upsert", Map.of(
                    "projectName", projectName,
                    "planName", "safe",
                    "payload", securityPlanPayload), true));
            JsonNode securityPlanUpsert = toolPayload(server.responseFor(28));
            assertThat(securityPlanUpsert.path("status").asText()).isEqualTo("succeeded");
            assertThat(workspace.resolve(".mcpjvm").resolve(projectName)
                    .resolve("plans/security/safe/contract.json"))
                    .isRegularFile();

            server.send(executeRequest(22, "artifact_management.project_context.read", Map.of(
                    "projectName", projectName), false));
            assertThat(toolPayload(server.responseFor(22)).path("status").asText())
                    .isEqualTo("succeeded");

            server.send(executeRequest(23, "transport_execute.execute", Map.of(
                    "protocol", "http",
                    "request", Map.of(
                            "method", "GET",
                            "url", healthUrl)), true));
            JsonNode transport = toolPayload(server.responseFor(23));
            assertThat(transport.path("status").asText()).isEqualTo("succeeded");
            assertThat(transport.path("result").path("status").asText()).isEqualTo("pass");
            assertThat(transport.path("result").path("statusCode").asInt()).isEqualTo(200);
            assertThat(transport.toString()).doesNotContain(authorization);

            Map<String, Object> suiteArguments = Map.of(
                    "projectName", projectName,
                    "executionProfile", "smoke",
                    "planName", planName,
                    "suiteRunId", suiteRunId);
            server.send(executeRequest(24, "regression_suite.preflight", suiteArguments, false));
            JsonNode preflight = toolPayload(server.responseFor(24));
            assertThat(preflight.path("status").asText()).isEqualTo("succeeded");
            assertThat(preflight.path("result").path("status").asText())
                    .as(preflight.toPrettyString())
                    .isEqualTo("ready");
            assertThat(preflight.path("result").path("reasonCode").asText()).isEqualTo("ok");

            server.send(executeRequest(25, "regression_suite.execute_plan", suiteArguments, false));
            JsonNode confirmation = toolPayload(server.responseFor(25));
            assertThat(confirmation.path("status").asText()).isEqualTo("confirmation_required");

            server.send(executeRequest(26, "regression_suite.execute_plan", suiteArguments, true));
            JsonNode execution = toolPayload(server.responseFor(26, Duration.ofSeconds(30)));
            assertThat(execution.path("status").asText()).isEqualTo("succeeded");
            assertThat(execution.path("result").path("status").asText())
                    .as(execution.toPrettyString())
                    .isEqualTo("ready");
            assertThat(execution.path("result").path("reasonCode").asText()).isEqualTo("ok");
            assertThat(execution.path("result").path("details").path("runStatus").asText())
                    .isEqualTo("pass");
            assertThat(execution.path("result").path("details").path("watchers"))
                    .hasSize(1);
            assertThat(execution.path("result").path("details").path("watchers")
                    .get(0).path("status").asText()).isEqualTo("pass");
            assertThat(execution.path("result").path("details").path("externalVerification"))
                    .hasSize(2)
                    .allSatisfy(result -> assertThat(result.path("status").asText()).isEqualTo("pass"));
            JsonNode sqlFirstRow = execution.path("result").path("details").path("externalVerification")
                    .get(1).path("sql").path("firstRow");
            assertThat(sqlFirstRow.has("nullable")).as(sqlFirstRow.toPrettyString()).isTrue();
            assertThat(sqlFirstRow.path("nullable").isNull()).isTrue();
            assertThat(sqlFirstRow.path("literal").asText()).isEqualTo(":literal");

            server.send(executeRequest(29, "execution_orchestration.execute", Map.of(
                    "projectName", projectName,
                    "executionProfile", "smoke",
                    "suiteRunId", suiteRunId + "-orchestrated",
                    "maxPlansPerCall", 1), true));
            JsonNode orchestration = toolPayload(server.responseFor(29, Duration.ofSeconds(30)));
            assertThat(orchestration.path("status").asText()).isEqualTo("succeeded");
            assertThat(orchestration.path("result").path("status").asText())
                    .as(orchestration.toPrettyString())
                    .isEqualTo("pass");
            assertThat(orchestration.path("result").path("reasonCode").asText()).isEqualTo("ok");

            String exportId = "export-" + suiteRunId;
            server.send(executeRequest(30, "execution_profile_export.export", Map.of(
                    "projectName", projectName,
                    "exportId", exportId,
                    "executionProfile", "smoke",
                    "mode", "ps1",
                    "includeRuntimeStartup", false,
                    "includeHealthcheckGate", false), true));
            JsonNode export = toolPayload(server.responseFor(30));
            assertThat(export.path("status").asText()).isEqualTo("succeeded");
            assertThat(export.path("result").path("status").asText()).isEqualTo("ok");
            assertThat(workspace.resolve(".mcpjvm").resolve(projectName)
                    .resolve("exports").resolve(exportId).resolve("manifest.json"))
                    .isRegularFile();

            server.send(executeRequest(31, "security_suite.execute_plan", Map.of(
                    "projectName", projectName,
                    "executionProfile", "security-smoke",
                    "planName", "safe",
                    "suiteRunId", suiteRunId + "-security"), true));
            JsonNode security = toolPayload(server.responseFor(31, Duration.ofSeconds(30)));
            assertThat(security.path("status").asText()).isEqualTo("succeeded");
            assertThat(security.path("result").path("status").asText())
                    .as(security.toPrettyString())
                    .isEqualTo("completed");
            assertThat(security.path("result").path("details").path("runStatus").asText())
                    .isEqualTo("pass");

            server.send(executeRequest(27, "transport_execute.execute", Map.of(
                    "protocol", "http",
                    "request", Map.of(
                            "method", "GET",
                            "url", healthUrl,
                            "headers", Map.of("Authorization", authorization))), true));
            JsonNode redactedFailure = toolPayload(server.responseFor(27));
            assertThat(redactedFailure.path("status").asText()).isEqualTo("succeeded");
            assertThat(redactedFailure.path("result").path("status").asText())
                    .isEqualTo("fail_http");
            assertThat(redactedFailure.toString()).doesNotContain(authorization);
            assertThat(server.stderrText()).doesNotContain(authorization);

            Path runs = workspace.resolve(".mcpjvm").resolve(projectName)
                    .resolve("plans/regression").resolve(planName).resolve("runs");
            try (var paths = Files.walk(runs)) {
                assertThat(paths.filter(path -> path.getFileName().toString().equals("execution.result.json")))
                        .hasSize(2)
                        .allSatisfy(path -> {
                            try {
                                JsonNode persisted = JSON.readTree(path.toFile());
                                assertThat(persisted.path("status").asText()).isEqualTo("pass");
                            } catch (IOException exception) {
                                throw new AssertionError(exception);
                            }
                        });
            }

                server.closeInputAndAwaitTermination();
                assertThat(server.stdoutLines()).allSatisfy(this::assertJsonRpcMessage);
                assertThat(server.exitCode()).isZero();
            }
        } finally {
            try {
                if (target != null) {
                    target.close();
                }
            } finally {
                deleteRecursively(workspace);
            }
        }
    }

}
