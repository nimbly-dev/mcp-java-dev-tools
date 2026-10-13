package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("regression")
@Timeout(120)
class McpServerRegressionRequestIT extends RegressionMcpSupport {
    @Test
    void catalogDescribeAndSelectorValidationMatchTheExecutableRegressionSurface() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (McpServerProcess server = start(workspace)) {
            server.send(request(20, "tools/call", Map.of("name", "operation_catalog", "arguments", Map.of("api", "regression_suite"))));
            assertThat(toolPayload(server.responseFor(20)).path("entries"))
                    .extracting(entry -> entry.path("operationId").asText())
                    .containsExactlyInAnyOrder("regression_suite.preflight", "regression_suite.execute_plan");
            for (String operation : new String[] {"regression_suite.preflight", "regression_suite.execute_plan"}) {
                server.send(request(21, "tools/call", Map.of("name", "operation_describe", "arguments", Map.of("operationId", operation))));
                JsonNode described = toolPayload(server.responseFor(21)).path("operation");
                assertThat(described.path("inputSchema").path("required")).extracting(JsonNode::asText)
                        .containsExactlyInAnyOrder("projectName", "executionProfile", "planName", "suiteRunId");
                assertThat(described.path("safety").path("confirmationRequired").asBoolean())
                        .isEqualTo(operation.endsWith("execute_plan"));
                assertThat(described.path("safety").path("cancellationSupported").isBoolean()).isTrue();
                assertThat(described.path("safety").path("cancellationSupported").asBoolean()).isFalse();
                assertThat(execute(server, operation, Map.of("projectName", PROJECT), true).path("status").asText())
                        .isEqualTo("invalid_input");
            }
        }
    }

    @Test
    @Tag("regression-smoke")
    void orderedRequestsExtractContextAndAssertActualResponses() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "requests"));
            ObjectNode plan = generatedPlan(target.url("/first"));
            ObjectNode first = step(plan, 0);
            http(first).put("method", "POST").put("url", target.url("/orders/a%20b?item=one%2Ftwo&item=three"));
            http(first).putObject("headers").put("X-Scenario", "${tenant}");
            http(first).putObject("body").put("quantity", 2);
            expect(first, "response.headers.x-fixture", "field_equals", "observed");
            expect(first, "response.bodyJson.safe", "field_matches_regex", "^observed$");
            expect(first, "response.body", "contains", "observed");
            first.putArray("extract").addObject().put("from", "response.bodyJson.safe").put("as", "observed").put("required", true);
            ObjectNode second = first.deepCopy().put("order", 2).put("id", "second");
            http(second).put("url", target.url("/next/${observed}"));
            second.putObject("when").put("left", "step[1].response.status").put("op", "equals").put("right", 200);
            contract(plan).withArray("steps").add(second);
            save(server, "requests", plan);
            assertThat(execute(server, "regression_suite.preflight", selector("requests", "ordered"), false)
                    .path("result").path("status").asText()).isEqualTo("ready");
            assertThat(execute(server, "regression_suite.execute_plan", selector("requests", "ordered"), false)
                    .path("status").asText()).isEqualTo("confirmation_required");
            target.assertNoRequest();
            JsonNode result = run(server, "requests", "ordered");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo("pass");
            assertThat(result.path("details").path("steps")).hasSize(2);
            TransportHttpTarget.Received received = target.received();
            assertThat(received.uri()).isEqualTo("/orders/a%20b?item=one%2Ftwo&item=three");
            assertThat(received.method()).isEqualTo("POST");
            assertThat(received.header("X-Scenario")).isEqualTo("test-tenant");
            assertThat(JSON.readTree(received.body()).path("quantity").asInt()).isEqualTo(2);
            assertThat(target.received().uri()).isEqualTo("/next/observed");
            target.assertNoRequest();
            assertRun(server, workspace, "requests", "ordered", "pass");
            // This smoke uses only generated non-secret fixture data; retain its actual CDE wire evidence.
            Files.write(workspace.getParent().resolve("smoke-stdio.jsonl"), server.stdoutLines());
        }
    }

    @Test
    void suiteExtractionIsExplicitAndSurvivesPlanSliceRestart() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start()) {
            Map<String, Object> input = Map.of("projectName", PROJECT, "executionProfile", PROFILE,
                    "suiteRunId", "promoted", "maxPlansPerCall", 1);
            try (McpServerProcess server = start(workspace)) {
                saveContext(server, context(workspace, target.url(""), "producer", "consumer"));
                ObjectNode producer = generatedPlan(target.url("/producer"));
                step(producer, 0).putArray("extract").addObject().put("from", "response.bodyJson.safe")
                        .put("as", "item").put("scope", "suite").put("secret", false);
                save(server, "producer", producer);
                ObjectNode consumer = generatedPlan(target.url("/consumer/${suite.item}"));
                save(server, "consumer", consumer);
                JsonNode slice = execute(server, "execution_orchestration.execute", input, true).path("result");
                assertThat(slice.path("status").asText()).isEqualTo("in_progress");
                assertThat(slice.path("details").path("suiteContext").path("suite.item").asText()).isEqualTo("observed");
                assertThat(target.received().uri()).isEqualTo("/producer");
            }
            try (McpServerProcess restarted = start(workspace)) {
                JsonNode result = execute(restarted, "execution_orchestration.execute", input, true).path("result");
                assertThat(result.path("status").asText()).isEqualTo("pass");
                assertThat(target.received().uri()).isEqualTo("/consumer/observed");
                target.assertNoRequest();
            }
        }
    }
}
