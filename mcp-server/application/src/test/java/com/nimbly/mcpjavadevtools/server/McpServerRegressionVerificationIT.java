package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("regression")
@Timeout(120)
class McpServerRegressionVerificationIT extends RegressionMcpSupport {
    @Test
    void lateWatcherResponseCannotPassAfterTheAbsoluteDeadline() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/watch")) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            TransportHttpTarget.respond(exchange, 200, "{\"state\":\"ready\"}");
        }); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "late"));
            ObjectNode plan = generatedPlan(target.url("/trigger"));
            ObjectNode watcher = watcher(plan, target.url("/watch"));
            ((ObjectNode) watcher.path("waitPolicy")).put("timeoutMs", 50).put("retryMax", 1);
            save(server, "late", plan);
            JsonNode result = run(server, "late", "late");
            assertThat(result.path("reasonCode").asText()).isEqualTo("watcher_timeout");
            assertThat(target.received().uri()).isEqualTo("/trigger");
            assertThat(target.received().uri()).isEqualTo("/watch");
            assertRun(server, workspace, "late", "late", "fail");
        }
    }

    @Test
    void triggerTimeoutPersistsFailureAndDoesNotExecuteVerification() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (DelayedHttpTarget target = DelayedHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(), "timeout"));
            ObjectNode plan = generatedPlan(target.url());
            http(step(plan, 0)).put("timeoutMs", 100);
            external(plan, "http://127.0.0.1:" + freePort() + "/must-not-verify");
            save(server, "timeout", plan);
            JsonNode result = run(server, "timeout", "trigger-timeout");
            target.awaitRequest();
            assertThat(result.path("reasonCode").asText()).isEqualTo("transport_request_timeout");
            assertThat(result.path("details").has("externalVerification")).isFalse();
            target.awaitClientDisconnect();
            assertRun(server, workspace, "timeout", "trigger-timeout", "fail");
        }
    }

    @ParameterizedTest
    @CsvSource({"watcher,watcher_target_unreachable", "external,external_verification_transport_failed", "extract,external_verification_extract_missing"})
    void unreachableVerificationAndMissingExtractionFailClosed(String variant, String reason) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, "{\"state\":\"ready\"}")); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "blocked"));
            ObjectNode plan = generatedPlan(target.url("/trigger"));
            String unreachable = "http://127.0.0.1:" + freePort() + "/unreachable";
            if (variant.equals("watcher")) {
                watcher(plan, unreachable);
            } else {
                ObjectNode verification = external(plan, variant.equals("extract") ? target.url("/verify") : unreachable);
                if (variant.equals("extract")) {
                    verification.putArray("extract").addObject().put("from", "response.bodyJson.absent")
                            .put("as", "missing").put("required", true);
                }
            }
            save(server, "blocked", plan);
            JsonNode result = run(server, "blocked", variant);
            assertThat(result.path("reasonCode").asText()).as(result.toPrettyString()).isEqualTo(reason);
            assertRun(server, workspace, "blocked", variant, "fail");
            assertThat(target.received().uri()).isEqualTo("/trigger");
            if (variant.equals("extract")) {
                assertThat(target.received().uri()).isEqualTo("/verify");
            }
            target.assertNoRequest();
        }
    }

    @Test
    void watchersAndExternalHttpSqlVerifyStateCreatedByTheActualTrigger() throws Exception {
        Path workspace = JavaItScenario.workspace();
        String database = "jdbc:sqlite:" + workspace.resolve("downstream.db");
        try (var connection = DriverManager.getConnection(database); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE effects (id TEXT PRIMARY KEY, state TEXT)");
        }
        AtomicInteger polls = new AtomicInteger();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/trigger")) {
                try (var connection = DriverManager.getConnection(database); var statement = connection.createStatement()) {
                    statement.execute("INSERT INTO effects VALUES ('created-by-trigger', 'ready')");
                } catch (java.sql.SQLException exception) {
                    throw new java.io.IOException(exception);
                }
                TransportHttpTarget.respond(exchange, 200, "{\"id\":\"created-by-trigger\"}");
            } else {
                TransportHttpTarget.respond(exchange, 200, polls.incrementAndGet() < 2
                        ? "{\"state\":\"pending\"}" : "{\"state\":\"ready\"}");
            }
        }); McpServerProcess server = start(workspace)) {
            ObjectNode project = context(workspace, target.url(""), "verify");
            ((ObjectNode) project.path("workspaces").get(0).path("executionProfiles").get(0).path("providedContext"))
                    .put("sql.connection.effects.kind", "sqlite").put("sql.connection.effects.sqlite.filePath", "downstream.db");
            saveContext(server, project);
            ObjectNode plan = generatedPlan(target.url("/trigger"));
            step(plan, 0).putArray("extract").addObject().put("from", "response.bodyJson.id").put("as", "createdId");
            watcher(plan, target.url("/watch/${createdId}"));
            external(plan, target.url("/external/${createdId}"));
            sql(plan);
            save(server, "verify", plan);
            JsonNode result = run(server, "verify", "real-effects");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo("pass");
            assertThat(result.path("details").path("watchers").get(0).path("attemptCount").asInt()).isEqualTo(2);
            assertThat(result.path("details").path("externalVerification")).hasSize(2);
            assertThat(result.path("details").path("externalVerification").get(1).path("sql").path("firstRow").path("state").asText())
                    .isEqualTo("ready");
            assertThat(target.received().uri()).isEqualTo("/trigger");
            assertThat(target.received().uri()).isEqualTo("/watch/created-by-trigger");
            assertThat(target.received().uri()).isEqualTo("/watch/created-by-trigger");
            assertThat(target.received().uri()).isEqualTo("/external/created-by-trigger");
            assertRun(server, workspace, "verify", "real-effects", "pass");
            JsonNode rows = query(server, "watcher_state", Map.of("runId", "real-effects-1")).path("items");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).path("state").get(0).path("status").asText()).isEqualTo("pass");
        }
    }

    @ParameterizedTest
    @CsvSource({"watcher,watcher_expectation_failed", "external,external_verification_expectation_failed"})
    void failedVerificationPersistsTheOwningPhaseAndReason(String phase, String reason) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "failure"));
            ObjectNode plan = generatedPlan(target.url("/trigger"));
            if (phase.equals("watcher")) {
                watcher(plan, target.url("/watch"));
                ((ObjectNode) contract(plan).path("watchers").get(0).path("waitPolicy")).put("retryMax", 1);
            } else {
                external(plan, target.url("/verify"));
            }
            save(server, "failure", plan);
            JsonNode result = run(server, "failure", phase);
            assertThat(result.path("status").asText()).isEqualTo("failed");
            assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
            String section = phase.equals("watcher") ? "watchers" : "externalVerification";
            assertThat(result.path("details").path(section).get(0).path("reasonCode").asText()).isEqualTo(reason);
            assertRun(server, workspace, "failure", phase, "fail");
            assertThat(target.received().uri()).isEqualTo("/trigger");
            assertThat(target.received().uri()).isEqualTo(phase.equals("watcher") ? "/watch" : "/verify");
            target.assertNoRequest();
        }
    }

    static ObjectNode watcher(ObjectNode plan, String url) {
        ObjectNode watcher = contract(plan).withArray("watchers").addObject().put("id", "completion");
        watcher.putObject("dependency").put("stepOrder", 1);
        watcher.putObject("provider").put("type", "http").putObject("transport").putObject("request")
                .put("method", "GET").put("url", url);
        watcher.putObject("waitPolicy").put("timeoutMs", 2000).put("retryMax", 3).put("intervalMs", 10);
        expect(watcher, "response.bodyJson.state", "field_equals", "ready");
        return watcher;
    }

    static ObjectNode external(ObjectNode plan, String url) {
        ObjectNode external = contract(plan).withArray("externalVerification").addObject().put("id", "external");
        external.putObject("provider").put("type", "http");
        external.putObject("request").putObject("http").put("method", "GET").put("url", url);
        expect(external, "response.bodyJson.state", "field_equals", "ready");
        external.putArray("extract").addObject().put("from", "response.bodyJson.state").put("as", "externalState");
        return external;
    }

    static void sql(ObjectNode plan) {
        ObjectNode external = contract(plan).withArray("externalVerification").addObject().put("id", "sql");
        external.putObject("provider").put("type", "sql");
        ObjectNode sql = external.putObject("request").putObject("sql").put("connectionRef", "effects")
                .put("statement", "SELECT state, ':literal' AS literal FROM effects WHERE id = :id");
        sql.putArray("parameters").addObject().put("name", "id").put("valueFromContext", "createdId");
        expect(external, "sql.firstRow.state", "field_equals", "${externalState}");
        expect(external, "sql.rowCount", "numeric_gte", 1);
    }
}
