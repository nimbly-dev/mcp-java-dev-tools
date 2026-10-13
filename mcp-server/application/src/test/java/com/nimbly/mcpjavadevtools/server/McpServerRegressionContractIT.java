package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("regression")
@Timeout(120)
class McpServerRegressionContractIT extends RegressionMcpSupport {
    @ParameterizedTest
    @CsvSource({
        "response.bodyJson.items,contains,first,pass",
        "response.bodyJson.number,numeric_gte,2,pass",
        "response.bodyJson.number,numeric_lte,2,pass",
        "response.bodyJson.number,numeric_gte,3,fail",
        "response.bodyJson.number,numeric_lte,1,fail",
        "response.bodyJson.number,field_equals,2,pass",
        "response.bodyJson.items[0],field_equals,first,pass",
        "response.bodyJson.items[0],field_exists,unused,pass",
        "response.bodyJson.items[0],field_matches_regex,^fir,pass",
        "response.bodyJson.items[0],field_matches_regex,[,fail",
        "response.bodyJson.items[0],numeric_gte,2,fail",
        "status,outcome_status,pass,pass",
        "response.bodyJson.missing,field_exists,unused,fail"
    })
    void expectationOperatorsUseRealJsonTypesAndIndexedPaths(String path, String operator, String expected, String status) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, "{\"items\":[\"first\"],\"number\":2}"));
                McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "operators"));
            ObjectNode plan = generatedPlan(target.url("/operators"));
            Object value = expected.matches("\\d+") ? Integer.valueOf(expected) : expected;
            expect(step(plan, 0), path, operator, value);
            save(server, "operators", plan);
            JsonNode result = run(server, "operators", "operators");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo(status);
            assertThat(result.path("reasonCode").asText()).isEqualTo(status.equals("pass") ? "ok" : "step_expectation_failed");
            assertThat(target.received().uri()).isEqualTo("/operators");
            assertRun(server, workspace, "operators", "operators", status);
        }
    }

    @ParameterizedTest
    @CsvSource({"equals,test-tenant,pass", "equals,other,skipped", "not_equals,other,pass", "not_equals,test-tenant,skipped",
        "exists,unused,pass", "in,test-tenant,pass", "in,other,skipped", "not,other,pass", "all,test-tenant,pass", "any,other,pass"})
    void conditionsUsePlanContextAndShortCircuitBeforeHttp(String operator, String right, String status) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "conditions"));
            ObjectNode plan = generatedPlan(target.url("/conditional"));
            step(plan, 0).set("when", condition(operator, right));
            save(server, "conditions", plan);
            JsonNode result = run(server, "conditions", "condition");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo("pass");
            assertThat(result.path("details").path("steps").get(0).path("status").asText()).isEqualTo(status);
            if (status.equals("pass")) {
                assertThat(target.received().uri()).isEqualTo("/conditional");
            }
            target.assertNoRequest();
        }
    }

    private ObjectNode condition(String operator, String right) {
        ObjectNode node = JSON.createObjectNode().put("left", "context.tenant");
        if (operator.equals("in")) {
            node.put("op", "in").putArray("right").add(right);
        } else if (operator.equals("not")) {
            node.remove("left");
            node.set("not", condition("equals", right));
        } else if (operator.equals("all") || operator.equals("any")) {
            node.remove("left");
            node.putArray(operator).add(condition("equals", right)).add(condition("exists", "unused"));
        } else {
            node.put("op", operator).put("right", right);
        }
        return node;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void secretOverwriteRevokesTheEarlierPublicSuiteClassification(boolean waiting) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, "{\"state\":\"pending\",\"value\":\""
                        + (exchange.getRequestURI().getPath().equals("/public") ? "visible" : SECRET) + "\"}"));
                McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "overwrite"));
            ObjectNode plan = generatedPlan(target.url("/public"));
            step(plan, 0).putArray("extract").addObject().put("from", "response.bodyJson.value")
                    .put("as", "item").put("scope", "suite").put("secret", false);
            ObjectNode second = step(plan, 0).deepCopy().put("order", 2).put("id", "secret");
            http(second).put("url", target.url("/secret"));
            second.putArray("extract").addObject().put("from", "response.bodyJson.value")
                    .put("as", "item").put("scope", "plan").put("secret", true);
            contract(plan).withArray("steps").add(second);
            if (waiting) {
                ((ObjectNode) plan.path("metadata").path("execution")).put("verificationBatchSize", 1);
                McpServerRegressionVerificationIT.watcher(plan, target.url("/watch"));
            }
            save(server, "overwrite", plan);
            JsonNode result = run(server, "overwrite", "overwrite");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo(waiting ? "in_progress" : "pass");
            assertThat(result.path("details").path("suiteContext").has("suite.item")).isFalse();
            assertThat(result.path("details").path("continuation").path("context").has("item")).isFalse();
            JsonNode persisted = assertRun(server, workspace, "overwrite", "overwrite", waiting ? "in_progress" : "pass");
            assertThat(persisted.toString()).doesNotContain(SECRET);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"artifact-secret-sentinel\"", "987654321", "\"review-\\\"quoted\\\"\\\\secret\"",
        "\"review-\\u0022quoted\\u0022\\u005csecret\""})
    void secretExternalExtractionIsRedactedFromVerificationEvidence(String literal) throws Exception {
        Path workspace = JavaItScenario.workspace();
        String secret = JSON.readTree(literal).asText();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, "{\"state\":\"ready\",\"value\":" + literal + "}"));
                McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "external-secret"));
            ObjectNode plan = generatedPlan(target.url("/trigger"));
            McpServerRegressionVerificationIT.watcher(plan, target.url("/watch"));
            ObjectNode external = McpServerRegressionVerificationIT.external(plan, target.url("/extract"));
            external.putArray("extract").addObject().put("from", "response.bodyJson.value")
                    .put("as", "value").put("secret", true);
            ObjectNode next = McpServerRegressionVerificationIT.external(plan, target.url("/use"));
            next.put("id", "use-secret");
            ((ObjectNode) next.path("request").path("http")).putObject("headers").put("Authorization", "Bearer ${value}");
            save(server, "external-secret", plan);
            JsonNode result = run(server, "external-secret", "external-secret");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo("pass");
            assertThat(target.received().uri()).isEqualTo("/trigger");
            assertThat(target.received().uri()).isEqualTo("/watch");
            assertThat(target.received().uri()).isEqualTo("/extract");
            assertThat(target.received().header("Authorization")).isEqualTo("Bearer " + secret);
            assertThat(result.toString()).doesNotContain(secret);
            JsonNode persisted = assertRun(server, workspace, "external-secret", "external-secret", "pass");
            for (String section : new String[] {"watchers", "externalVerification"}) {
                assertSecretResponse(result.path("details"), section);
                assertSecretResponse(persisted.path("details"), section);
            }
            try (var files = Files.walk(workspace)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain(secret);
                }
            }
        }
    }

    private void assertSecretResponse(JsonNode details, String section) {
        JsonNode response = details.path(section).get(0).path("response");
        assertThat(response.path("body").asText()).isEqualTo("[REDACTED]");
        assertThat(response.path("bodyJson").path("value").asText()).isEqualTo("[REDACTED]");
        assertThat(response.path("bodyJson").path("state").asText()).isEqualTo("ready");
    }

    @Test
    void explicitSecretExtractionIsUsableButAbsentFromOutputArtifactsAndSqlite() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(exchange ->
                TransportHttpTarget.respond(exchange, 200, "{\"value\":\"" + SECRET + "\"}"));
                TransportScenario scenario = TransportScenario.startWithSecrets(workspace, Map.of(), List.of(SECRET),
                        "--workspace-root=" + workspace)) {
            McpServerProcess server = scenario.server;
            saveContext(server, context(workspace, target.url(""), "secret"));
            ObjectNode plan = generatedPlan(target.url("/extract"));
            step(plan, 0).putArray("extract").addObject().put("from", "response.bodyJson.value")
                    .put("as", "value").put("secret", true).put("scope", "plan");
            ObjectNode second = step(plan, 0).deepCopy().put("order", 2).put("id", "use-secret");
            http(second).put("url", target.url("/use")).putObject("headers").put("Authorization", "Bearer ${value}");
            contract(plan).withArray("steps").add(second);
            save(server, "secret", plan);
            JsonNode result = run(server, "secret", "redaction");
            assertThat(result.path("details").path("runStatus").asText()).isEqualTo("pass");
            assertThat(target.received().uri()).isEqualTo("/extract");
            assertThat(target.received().header("Authorization")).isEqualTo("Bearer " + SECRET);
            assertRun(server, workspace, "secret", "redaction", "pass");
            assertThat(String.join("\n", server.stdoutLines())).doesNotContain(SECRET);
            try (var files = Files.walk(workspace)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain(SECRET);
                }
            }
        }
    }
}
