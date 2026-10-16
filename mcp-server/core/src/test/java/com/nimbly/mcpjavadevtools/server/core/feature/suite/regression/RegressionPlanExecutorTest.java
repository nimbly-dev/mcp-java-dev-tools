package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeBatchResetRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset.ProbeResetResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusEntry;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status.ProbeStatusResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation.RegressionCorrelationRuntime;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegressionPlanExecutorTest {

    @Test
    void resultsRetainDeclaredEndpointIdentityWithoutUrlCredentialsQueryOrResolvedContext() {
        for (String field : List.of("path", "pathTemplate", "url")) {
            ObjectNode input = validPlan();
            ObjectNode http = (ObjectNode) input.path("contract").path("steps").get(0).path("transport").path("http");
            http.remove("path");
            String endpoint = field.equals("url") ? "https://user:password@example.test/owners/${ownerId}" : "/owners/${ownerId}";
            http.put(field, endpoint + "?token=literal-secret#private-fragment");
            ((ObjectNode) input.path("providedContext")).put("ownerId", "resolved-private-id");
            RegressionSuiteResult result = feature.executePlan(input);
            var step = mapper.valueToTree(result.details()).path("steps").get(0);
            assertThat(step.path("method").asText()).isEqualTo("GET");
            assertThat(step.path("path").asText()).isEqualTo("/owners/${ownerId}");
            assertThat(result.toString()).doesNotContain("password", "literal-secret", "private-fragment", "resolved-private-id");
        }
    }

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegressionPlanPreflight preflight = new RegressionPlanPreflight();
    private final TransportExecutionFeature transport = request -> ExecuteTransportResult.httpResponse(
            "pass", "http", 200, java.util.Map.of(), "", 1);
    private final RegressionSuiteFeature feature = new RegressionPlanExecutor(preflight, transport, mapper);

    @Test
    void acceptsAReadyRegressionPlanWithoutExposingProvidedContext() {
        ObjectNode input = validPlan();
        input.putObject("providedContext").put("authorization", "sensitive-value");

        RegressionSuiteResult result = feature.preflight(input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.reasonCode()).isEqualTo("ok");
        assertThat(result.details()).containsEntry("targetCount", 1).containsEntry("stepCount", 1);
        assertThat(result.details().toString()).doesNotContain("sensitive-value");
    }

    @Test
    void resolvesProjectOwnedEnvironmentBindingsWithoutExposingTheirValues() throws Exception {
        Files.writeString(temporaryDirectory.resolve("regression.env"),
                "REGRESSION_AUTHORIZATION=Bearer fixture-secret\n");
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("contract").path("steps").get(0)
                .path("transport").path("http"))
                .putObject("headers").put("Authorization", "${authorization}");
        input.putObject("credentialBindings").put("authorization", "REGRESSION_AUTHORIZATION");
        input.putObject("credentialSource")
                .put("workspaceRoot", temporaryDirectory.toString())
                .put("envFile", "regression.env");
        ((ObjectNode) input.path("contract")).putArray("prerequisites").addObject()
                .put("key", "authorization")
                .put("required", true)
                .put("secret", true)
                .put("provisioning", "user_input");
        AtomicReference<ExecuteTransportRequest> observed = new AtomicReference<>();
        TransportExecutionFeature capturing = request -> {
            observed.set(request);
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "", 1);
        };

        RegressionSuiteResult result = new RegressionPlanExecutor(preflight, capturing, mapper).executePlan(input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(observed.get().request().get("headers"))
                .isEqualTo(Map.of("Authorization", "Bearer fixture-secret"));
        assertThat(result.toString()).doesNotContain("fixture-secret");
    }

    @Test
    void rejectsAPlanWithMissingStepExpectations() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("contract").path("steps").get(0)).remove("expect");

        RegressionSuiteResult result = feature.preflight(input);

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("step_expectations_missing");
        assertThat(result.reasonMeta()).containsEntry("failedStep", "preflight");
    }

    @Test
    void rejectsAPlanWithNonSequentialStepOrders() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("contract").path("steps").get(0)).put("order", 2);

        RegressionSuiteResult result = feature.preflight(input);

        assertThat(result.reasonCode()).isEqualTo("step_order_non_sequential");
    }

    @Test
    void rejectsAnInvalidPinnedStrictLineKey() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("metadata").path("execution"))
                .put("probeVerification", true).put("pinStrictProbeKey", true);
        ((ObjectNode) input.path("contract").path("targets").get(0))
                .putObject("runtimeVerification").put("strictProbeKey", "not-a-strict-key");

        RegressionSuiteResult result = feature.preflight(input);

        assertThat(result.status()).isEqualTo("stale_plan");
        assertThat(result.reasonCode()).isEqualTo("strict_probe_key_invalid");
    }

    @Test
    void executesStrictLineProbeVerificationAroundTheHttpTrigger() {
        ObjectNode input = validPlan();
        ObjectNode execution = (ObjectNode) input.path("metadata").path("execution");
        ObjectNode contract = (ObjectNode) input.path("contract");
        execution.put("probeVerification", true).put("pinStrictProbeKey", true);
        ((ObjectNode) contract.path("targets").get(0)).putObject("runtimeVerification")
                .put("strictProbeKey", "example.Health#check:12")
                .put("probeId", "fixture");
        ((ObjectNode) contract.path("steps").get(0)).withArray("expect").addObject()
                .put("id", "line_hit")
                .put("actualPath", "probe.hit")
                .put("operator", "probe_line_hit")
                .put("expected", true);
        AtomicInteger calls = new AtomicInteger();
        ProbeFeature probe = request -> {
            if (request instanceof ProbeBatchResetRequest) {
                calls.incrementAndGet();
                return ProbeResult.success(new ProbeResetResult(
                        "batch", null, "ok", List.of()));
            }
            calls.incrementAndGet();
            return ProbeResult.success(new ProbeStatusResult(List.of(new ProbeStatusEntry(
                    "example.Health#check:12", "example.Health#check:12", 200, true,
                    1L, System.currentTimeMillis(), true, "ok", null, null))));
        };
        RegressionSuiteFeature checked = new RegressionPlanExecutor(
                preflight, transport, mapper, RegressionCorrelationRuntime.unavailable(), probe);

        RegressionSuiteResult preflightResult = checked.preflight(input);
        RegressionSuiteResult executionResult = checked.executePlan(input);

        assertThat(preflightResult.status()).isEqualTo("ready");
        assertThat(executionResult.status()).isEqualTo("ready");
        assertThat(executionResult.details()).containsKey("probeVerification");
        assertThat(executionResult.details().get("probeVerification").toString())
                .contains("lineHit=true", "example.Health#check:12");
        assertThat(calls).hasValue(2);
    }

    @Test
    void autoResolvesStrictLineProbeKeyFromTheBoundWorkspace() throws Exception {
        Path source = temporaryDirectory.resolve("src/main/java/example/Health.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package example;
                public class Health {
                    public void check() {
                        System.out.println("ok");
                    }
                }
                """);
        ObjectNode input = validPlan();
        input.put("workspaceRootAbs", temporaryDirectory.toString());
        ((ObjectNode) input.path("metadata").path("execution"))
                .put("probeVerification", true).put("pinStrictProbeKey", false);
        ObjectNode target = (ObjectNode) input.path("contract").path("targets").get(0);
        target.putObject("selectors")
                .put("fqcn", "example.Health")
                .put("method", "check")
                .put("sourceRoot", "src/main/java");
        ((ObjectNode) input.path("contract").path("steps").get(0)).withArray("expect").addObject()
                .put("id", "line_hit")
                .put("actualPath", "probe.hit")
                .put("operator", "probe_line_hit")
                .put("expected", true);
        AtomicReference<String> resolvedKey = new AtomicReference<>();
        ProbeFeature probe = request -> {
            if (request instanceof ProbeBatchResetRequest reset) {
                resolvedKey.set(reset.keySelector().keys().getFirst());
                return ProbeResult.success(new ProbeResetResult("batch", null, "ok", List.of()));
            }
            String key = resolvedKey.get();
            return ProbeResult.success(new ProbeStatusResult(List.of(new ProbeStatusEntry(
                    key, key, 200, true, 1L, System.currentTimeMillis(), true,
                    "ok", null, null))));
        };
        RegressionSuiteFeature checked = new RegressionPlanExecutor(
                preflight, transport, mapper, RegressionCorrelationRuntime.unavailable(), probe);

        RegressionSuiteResult result = checked.executePlan(input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(resolvedKey).hasValue("example.Health#check:4");
        assertThat(result.details().get("probeVerification").toString())
                .contains("example.Health#check:4", "lineHit=true");
    }

    @Test
    void executesWatcherAndExternalHttpVerification() {
        ObjectNode input = validPlan();
        ObjectNode contract = (ObjectNode) input.path("contract");
        ((ObjectNode) input.path("providedContext")).put("expectedState", "ready");
        ObjectNode watcher = contract.putArray("watchers").addObject()
                .put("id", "completion");
        watcher.putObject("dependency").put("stepOrder", 1);
        watcher.putObject("provider").put("type", "http")
                .putObject("transport").putObject("request")
                .put("method", "GET").put("url", "https://example.test/watch");
        watcher.putArray("expect").addObject()
                .put("id", "ready").put("actualPath", "response.bodyJson.state")
                .put("operator", "field_equals").put("expected", "ready");
        ObjectNode external = contract.putArray("externalVerification").addObject()
                .put("id", "downstream");
        external.putObject("provider").put("type", "http");
        external.putObject("request").putObject("http")
                .put("method", "GET").put("url", "https://example.test/downstream");
        external.putArray("expect").addObject()
                .put("id", "ready").put("actualPath", "response.bodyJson.state")
                .put("operator", "field_equals").put("expected", "${expectedState}");
        TransportExecutionFeature responses = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 200, Map.of(), "{\"state\":\"ready\"}", 1);

        RegressionSuiteResult result = execute(responses, input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details()).containsKeys("watchers", "externalVerification");
        assertThat(result.details().get("watchers").toString()).contains("status=pass");
        assertThat(result.details().get("externalVerification").toString()).contains("status=pass");
    }

    @Test
    void executesExternalSqlVerificationThroughAConnectionReference() throws Exception {
        Path database = temporaryDirectory.resolve("verification.sqlite");
        String jdbcUrl = "jdbc:sqlite:" + database;
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE verification (id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO verification(id) VALUES (1)");
        }
        ObjectNode input = validPlan();
        input.put("workspaceRootAbs", temporaryDirectory.toString());
        ObjectNode context = (ObjectNode) input.path("providedContext");
        context.put("sql.connection.catalogDb.kind", "sqlite");
        context.put("sql.connection.catalogDb.sqlite.filePath", database.toString());
        context.put("verificationId", 1);
        ObjectNode external = ((ObjectNode) input.path("contract"))
                .putArray("externalVerification").addObject().put("id", "database");
        external.putObject("provider").put("type", "sql");
        ObjectNode sql = external.putObject("request").putObject("sql")
                .put("connectionRef", "catalogDb")
                .put("statement", "SELECT id FROM verification WHERE id = :literalId AND id = :contextId");
        sql.putArray("parameters").addObject()
                .put("name", "literalId").put("value", 1);
        sql.withArray("parameters").addObject()
                .put("name", "contextId").put("valueFromContext", "verificationId");
        external.putArray("expect").addObject()
                .put("id", "row").put("actualPath", "sql.firstRow.id")
                .put("operator", "field_equals").put("expected", 1);

        RegressionSuiteResult result = feature.executePlan(input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details().get("externalVerification").toString())
                .contains("provider=sql", "rowCount=1", "status=pass");
    }

    @Test
    void malformedProbeFlagCannotBeTreatedAsDisabled() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("metadata").path("execution")).put("probeVerification", "false");

        RegressionSuiteResult result = feature.executePlan(input);

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("probe_verification_configuration_invalid");
        assertThat(result.details()).doesNotContainKey("runStatus");
    }

    @Test
    void reportsMissingRequiredUserInputWithoutExposingValues() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("metadata").path("execution")).put("discoveryPolicy", "disabled");
        ((ObjectNode) input.path("contract")).putArray("prerequisites").addObject()
                .put("key", "authorization")
                .put("required", true)
                .put("secret", true)
                .put("provisioning", "user_input");

        RegressionSuiteResult result = feature.preflight(input);

        assertThat(result.status()).isEqualTo("needs_user_input");
        assertThat(result.reasonCode()).isEqualTo("missing_prerequisites_user_input");
        assertThat(result.reasonMeta()).containsEntry("missing", List.of("authorization"));
    }

    @Test
    void reportsDiscoverablePrerequisitesWhenDiscoveryIsAllowed() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("metadata").path("execution"))
                .put("discoveryPolicy", "allow_discoverable_prerequisites");
        ((ObjectNode) input.path("contract")).putArray("prerequisites").addObject()
                .put("key", "routeToken")
                .put("required", true)
                .put("secret", false)
                .put("provisioning", "discoverable");

        RegressionSuiteResult result = feature.preflight(input);

        assertThat(result.status()).isEqualTo("needs_discovery");
        assertThat(result.reasonCode()).isEqualTo("missing_prerequisites_discoverable");
        assertThat(result.reasonMeta()).containsEntry("discoverablePending", List.of("routeToken"));
    }

    @Test
    void executesOrderedHttpStepsThroughThePublicTransportFeature() {
        RegressionSuiteResult result = feature.executePlan(validPlan());

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details()).containsEntry("runStatus", "pass");
    }

    @Test
    void transportFailureWithoutAnHttpStatusKeepsItsDeterministicReason() {
        TransportExecutionFeature timeout = request -> ExecuteTransportResult.blockedRuntime(
                "transport_request_timeout", "request timed out", "http", 100);

        RegressionSuiteResult result = execute(timeout, validPlan());

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.reasonCode()).isEqualTo("transport_request_timeout");
        assertThat(result.details()).containsEntry("runStatus", "fail");
        assertThat(mapper.valueToTree(result.details()).path("steps").get(0).has("statusCode")).isFalse();
    }

    @Test
    void secretValueEqualToAnOwnerStatusDoesNotChangeTheControlResult() {
        ObjectNode input = validPlan();
        ObjectNode external = ((ObjectNode) input.path("contract")).putArray("externalVerification").addObject().put("id", "secret");
        external.putObject("provider").put("type", "http");
        external.putObject("request").putObject("http").put("url", "https://example.test/secret");
        external.putArray("expect").addObject().put("id", "status").put("actualPath", "response.statusCode")
                .put("operator", "field_equals").put("expected", 200);
        external.putArray("extract").addObject().put("from", "response.bodyJson.value").put("as", "value").put("secret", true);
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 200, Map.of(), "{\"value\":\"ready\"}", 1);

        RegressionSuiteResult result = execute(response, input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.reasonCode()).isEqualTo("ok");
        assertThat(result.details()).containsEntry("runStatus", "pass");
        assertThat(mapper.valueToTree(result.details()).path("externalVerification").get(0)
                .path("response").path("bodyJson").path("value").asText()).isEqualTo("[REDACTED]");
    }

    @Test
    void correlatesResponseDerivedProducerKeyWithConsumerRuntimeEvidence() {
        ObjectNode input = correlatedPlan();
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 202, Map.of(), "{\"eventId\":\"evt-1\"}", 1);
        RegressionCorrelationRuntime runtime = (correlation, executionId) -> session(List.of(Map.ofEntries(
                Map.entry("eventId", "consumer:1"),
                Map.entry("probeId", "event-consumer-app"),
                Map.entry("runtimeInstanceId", "runtime-1"),
                Map.entry("lineKey", "example.Listener#receive:20"),
                Map.entry("timestampEpochMs", System.currentTimeMillis() + 10L),
                Map.entry("keyType", "messageId"),
                Map.entry("keyValue", "evt-1"),
                Map.entry("eventType", "runtime_line_hit"))));

        RegressionSuiteResult result = new RegressionPlanExecutor(preflight, response, mapper, runtime)
                .executePlan(input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details()).containsEntry("runStatus", "pass");
        assertThat(result.details().get("correlation").toString())
                .contains("status=ok", "event-producer-app", "event-consumer-app", "runtime_line_hit");
    }

    @Test
    void rejectsRuntimeEvidenceOutsideTheConfiguredInstanceScope() {
        ObjectNode input = correlatedPlan();
        ((ObjectNode) input.path("contract").path("correlation").path("runtimeEvidence"))
                .putArray("runtimeInstanceIds").add("runtime-a");
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 202, Map.of(), "{\"eventId\":\"evt-1\"}", 1);
        RegressionCorrelationRuntime runtime = (correlation, executionId) -> session(List.of(Map.ofEntries(
                Map.entry("eventId", "consumer:1"),
                Map.entry("probeId", "event-consumer-app"),
                Map.entry("runtimeInstanceId", "runtime-b"),
                Map.entry("timestampEpochMs", System.currentTimeMillis() + 10L),
                Map.entry("keyType", "messageId"),
                Map.entry("keyValue", "evt-1"),
                Map.entry("eventType", "runtime_line_hit"))));

        RegressionSuiteResult result = new RegressionPlanExecutor(preflight, response, mapper, runtime)
                .executePlan(input);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.reasonCode()).isEqualTo("correlation_probe_scope_mismatch");
    }

    @Test
    void rejectsCompetingSamePlanRuntimeIdentities() {
        ObjectNode input = correlatedPlan();
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 202, Map.of(), "{\"eventId\":\"evt-1\"}", 1);
        long observedAt = System.currentTimeMillis() + 10L;
        RegressionCorrelationRuntime runtime = (correlation, executionId) -> session(List.of(
                runtimeEvent("consumer:1", "runtime-a", observedAt),
                runtimeEvent("consumer:2", "runtime-b", observedAt + 1L)));

        RegressionSuiteResult result = new RegressionPlanExecutor(preflight, response, mapper, runtime)
                .executePlan(input);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.reasonCode()).isEqualTo("ambiguous_correlation");
    }

    @Test
    void failsClosedWhenTheConfiguredResponseCorrelationKeyIsMissing() {
        ObjectNode input = correlatedPlan();
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 202, Map.of(), "{}", 1);
        RegressionCorrelationRuntime runtime = (correlation, executionId) -> session(List.of());

        RegressionSuiteResult result = new RegressionPlanExecutor(preflight, response, mapper, runtime)
                .executePlan(input);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.reasonCode()).isEqualTo("missing_correlation_key");
        assertThat(result.details().get("correlation").toString())
                .contains("status=fail_closed", "missing_correlation_key");
    }

    @Test
    void persistsCrossPlanEvidenceWithoutRequiringTheWholeFlowInOnePlan() {
        ObjectNode input = correlatedPlan();
        ObjectNode correlation = (ObjectNode) input.path("contract").path("correlation");
        correlation.put("crossPlan", true);
        ((ObjectNode) correlation.path("runtimeEvidence")).put("required", false);
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 202, Map.of(), "{\"eventId\":\"evt-1\"}", 1);
        RegressionCorrelationRuntime runtime = (configuration, executionId) -> session(List.of());

        RegressionSuiteResult result = new RegressionPlanExecutor(preflight, response, mapper, runtime)
                .executePlan(input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details().get("correlation").toString())
                .contains("crossPlan=true", "event-producer-app");
    }

    @Test
    void resolvesPromotedSuiteCorrelationKeyInALaterPlanPolicy() {
        ObjectNode input = correlatedPlan();
        ObjectNode correlation = (ObjectNode) input.path("contract").path("correlation");
        correlation.put("crossPlan", true);
        ((ObjectNode) correlation.path("runtimeEvidence")).put("required", false);
        ObjectNode key = (ObjectNode) correlation.path("key");
        key.remove("source");
        key.put("value", "${suite.correlation.event-flow.keyValue}");
        ((ObjectNode) input.path("providedContext")).put(
                "suite.correlation.event-flow.keyValue", "evt-123");
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 202, Map.of(), "{}", 1);
        RegressionCorrelationRuntime runtime = (configuration, executionId) -> session(List.of());

        RegressionSuiteResult result = new RegressionPlanExecutor(preflight, response, mapper, runtime)
                .executePlan(input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details().get("correlation").toString())
                .contains("keyValue=evt-123").doesNotContain("${suite.correlation");
    }

    @Test
    void synthesizesCanonicalHttpUrlFromApiBaseUrlAndPath() {
        AtomicReference<Map<String, Object>> request = new AtomicReference<>();
        TransportExecutionFeature recording = input -> {
            request.set(((ExecuteTransportRequest) input).request());
            return ExecuteTransportResult.httpResponse("pass", "http", 200, Map.of(), "", 1);
        };

        RegressionSuiteResult result = execute(recording, validPlan());

        assertThat(result.status()).isEqualTo("ready");
        assertThat(request.get()).containsEntry("url", "https://example.test/health");
    }

    @Test
    void skipsAFalseConditionBeforeInvokingTransport() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("contract").path("steps").get(0)).putObject("when")
                .put("left", "context.run")
                .put("op", "equals")
                .put("right", "enabled");
        TransportExecutionFeature forbidden = request -> {
            throw new AssertionError("a false condition must not invoke transport");
        };

        RegressionSuiteResult result = execute(forbidden, input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details().toString()).contains("skipped", "step_condition_false");
    }

    @Test
    void evaluatesCompositeConditionsWithoutInvertingTheirMeaning() {
        ObjectNode allInput = validPlan();
        var all = ((ObjectNode) allInput.path("contract").path("steps").get(0)).putObject("when").putArray("all");
        all.addObject().put("left", "context.apiBaseUrl").put("op", "exists");
        all.addObject().put("left", "context.run").put("op", "equals").put("right", "enabled");
        ((ObjectNode) allInput.path("providedContext")).put("run", "enabled");

        RegressionSuiteResult allResult = execute(transport, allInput);

        ObjectNode anyInput = validPlan();
        var any = ((ObjectNode) anyInput.path("contract").path("steps").get(0)).putObject("when").putArray("any");
        any.addObject().put("left", "context.run").put("op", "equals").put("right", "enabled");
        any.addObject().put("left", "context.apiBaseUrl").put("op", "equals").put("right", "other");
        RegressionSuiteResult anyResult = execute(transport, anyInput);

        assertThat(allResult.details()).containsEntry("runStatus", "pass");
        assertThat(anyResult.details().toString()).contains("skipped", "step_condition_false");
    }

    @Test
    void evaluatesHeaderJsonAndBodyExpectations() {
        ObjectNode input = validPlan();
        ObjectNode step = (ObjectNode) input.path("contract").path("steps").get(0);
        step.remove("expect");
        var expectations = step.putArray("expect");
        expectations.addObject().put("id", "header").put("actualPath", "response.headers.X-Result")
                .put("operator", "field_equals").put("expected", "accepted");
        expectations.addObject().put("id", "json").put("actualPath", "response.bodyJson.id")
                .put("operator", "field_equals").put("expected", "order-7");
        expectations.addObject().put("id", "body").put("actualPath", "response.body")
                .put("operator", "contains").put("expected", "order-7");
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 200, Map.of("X-Result", "accepted"), "{\"id\":\"order-7\"}", 1);

        RegressionSuiteResult result = execute(response, input);

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details()).containsEntry("runStatus", "pass");
    }

    @Test
    void failsNumericLteWhenTheActualValueIsNotNumeric() {
        ObjectNode input = validPlan();
        ObjectNode expectation = (ObjectNode) input.path("contract").path("steps").get(0).path("expect").get(0);
        expectation.put("actualPath", "response.body");
        expectation.put("operator", "numeric_lte");
        expectation.put("expected", 200);
        TransportExecutionFeature response = request -> ExecuteTransportResult.httpResponse(
                "pass", "http", 200, Map.of(), "not-a-number", 1);

        RegressionSuiteResult result = execute(response, input);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.reasonCode()).isEqualTo("step_expectation_failed");
    }

    @Test
    void evaluatesAndExtractsIndexedJsonPaths() {
        ObjectNode input = validPlan();
        ObjectNode firstStep = (ObjectNode) input.path("contract").path("steps").get(0);
        ObjectNode expectation = (ObjectNode) firstStep.path("expect").get(0);
        expectation.put("actualPath", "response.bodyJson.records[0].type");
        expectation.put("expected", "primary");
        firstStep.putArray("extract")
                .addObject()
                .put("from", "response.bodyJson.records[0].type")
                .put("as", "recordType");
        ObjectNode secondStep = ((ArrayNode) input.path("contract").path("steps")).addObject()
                .put("order", 2)
                .put("id", "conditional")
                .put("protocol", "http");
        secondStep.putObject("when")
                .put("left", "context.recordType")
                .put("op", "equals")
                .put("right", "primary");
        secondStep.putObject("transport").putObject("http").put("method", "GET").put("path", "/conditional");
        secondStep.putArray("expect")
                .addObject()
                .put("id", "status")
                .put("actualPath", "response.status")
                .put("operator", "field_equals")
                .put("expected", 200);
        AtomicInteger calls = new AtomicInteger();
        TransportExecutionFeature response = request -> {
            calls.incrementAndGet();
            return ExecuteTransportResult.httpResponse(
                    "pass", "http", 200, Map.of(), "{\"records\":[{\"type\":\"primary\"}]}", 1);
        };

        RegressionSuiteResult result = execute(response, input);

        assertThat(result.details()).containsEntry("runStatus", "pass");
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectsInvalidConditionExpectationAndExtractionDefinitionsDuringPreflight() {
        ObjectNode invalidOperator = validPlan();
        ((ObjectNode) invalidOperator.path("contract").path("steps").get(0).path("expect").get(0))
                .put("operator", "unknown");
        RegressionSuiteResult operatorResult = feature.preflight(invalidOperator);

        ObjectNode forwardReference = validPlan();
        ((ObjectNode) forwardReference.path("contract").path("steps").get(0)).putObject("when")
                .put("left", "step[1].response.status")
                .put("op", "equals")
                .put("right", 200);
        RegressionSuiteResult conditionResult = feature.preflight(forwardReference);

        ObjectNode invalidExtraction = validPlan();
        ((ObjectNode) invalidExtraction.path("contract").path("steps").get(0)).putArray("extract")
                .addObject().put("from", "response.status").put("as", "status").put("scope", "invalid");
        RegressionSuiteResult extractionResult = feature.preflight(invalidExtraction);

        assertThat(operatorResult.reasonCode()).isEqualTo("step_expectation_invalid");
        assertThat(conditionResult.reasonCode()).isEqualTo("step_condition_forward_reference");
        assertThat(extractionResult.reasonCode()).isEqualTo("step_extract_invalid");
    }

    private RegressionSuiteResult execute(TransportExecutionFeature transportFeature, ObjectNode input) {
        return new RegressionPlanExecutor(preflight, transportFeature, mapper).executePlan(input);
    }

    private RegressionCorrelationRuntime.Session session(List<Map<String, Object>> events) {
        return new RegressionCorrelationRuntime.Session() {
            private boolean delivered;

            @Override
            public List<Map<String, Object>> readEvents() {
                if (delivered) {
                    return List.of();
                }
                delivered = true;
                return events;
            }

            @Override
            public void close() {
            }
        };
    }

    private Map<String, Object> runtimeEvent(String eventId, String runtimeInstanceId, long timestamp) {
        return Map.ofEntries(
                Map.entry("eventId", eventId),
                Map.entry("probeId", "event-consumer-app"),
                Map.entry("runtimeInstanceId", runtimeInstanceId),
                Map.entry("timestampEpochMs", timestamp),
                Map.entry("keyType", "messageId"),
                Map.entry("keyValue", "evt-1"),
                Map.entry("eventType", "runtime_line_hit"));
    }

    private ObjectNode correlatedPlan() {
        ObjectNode input = validPlan();
        ObjectNode target = (ObjectNode) input.path("contract").path("targets").get(0);
        target.putObject("runtimeVerification").put("probeId", "event-producer-app");
        ObjectNode step = (ObjectNode) input.path("contract").path("steps").get(0);
        step.put("targetRef", 0);
        ObjectNode expectation = (ObjectNode) step.path("expect").get(0);
        expectation.put("expected", 202);
        ObjectNode correlation = ((ObjectNode) input.path("contract")).putObject("correlation");
        correlation.put("enabled", true).put("crossPlan", false)
                .put("correlationSessionId", "unit-event-correlation");
        correlation.putObject("key").put("type", "messageId")
                .putObject("source").put("type", "json_path").put("path", "response.bodyJson.eventId");
        correlation.putObject("window").put("maxWindowMs", 5_000);
        correlation.putArray("probeIds").add("event-producer-app").add("event-consumer-app");
        correlation.putArray("expectedFlow").add("event-producer-app").add("event-consumer-app");
        correlation.putObject("runtimeEvidence").put("required", true)
                .put("eventKeyPath", "$.eventId").put("maxDurationMs", 1_000)
                .putArray("probeIds").add("event-consumer-app");
        return input;
    }

    private ObjectNode validPlan() {
        ObjectNode input = mapper.createObjectNode();
        input.putObject("providedContext").put("apiBaseUrl", "https://example.test");
        input.putObject("metadata").putObject("execution")
                .put("intent", "regression")
                .put("probeVerification", false)
                .put("pinStrictProbeKey", false);
        ObjectNode contract = input.putObject("contract");
        contract.putArray("targets").addObject();
        ObjectNode step = contract.putArray("steps").addObject()
                .put("order", 1)
                .put("id", "health")
                .put("protocol", "http");
        step.putObject("transport").putObject("http").put("method", "GET").put("path", "/health");
        step.putArray("expect").addObject()
                .put("id", "status")
                .put("actualPath", "response.status")
                .put("operator", "field_equals")
                .put("expected", 200);
        return input;
    }
}
