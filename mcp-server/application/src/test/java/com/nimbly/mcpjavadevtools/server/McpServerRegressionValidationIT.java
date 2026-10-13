package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("regression")
@Timeout(120)
class McpServerRegressionValidationIT extends RegressionMcpSupport {
    @ParameterizedTest
    @MethodSource("invalidContracts")
    void invalidContractsAreRejectedBeforeAnyEffect(String pointer, String json, String reason) throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "invalid"));
            ObjectNode plan = generatedPlan(target.url("/must-not-trigger"));
            if (pointer.startsWith("/contract/watchers/0")) {
                McpServerRegressionVerificationIT.watcher(plan, target.url("/must-not-watch"));
            }
            if (pointer.startsWith("/contract/externalVerification/0")) {
                McpServerRegressionVerificationIT.external(plan, target.url("/must-not-verify"));
            }
            int split = pointer.lastIndexOf('/');
            ((ObjectNode) plan.at(pointer.substring(0, split))).set(pointer.substring(split + 1), JSON.readTree(json));
            ok(server, "regression_plan.upsert", Map.of("projectName", PROJECT, "planName", "invalid", "payload", plan));
            JsonNode preflight = execute(server, "regression_suite.preflight", selector("invalid", "invalid"), false).path("result");
            assertThat(preflight.path("reasonCode").asText()).as(pointer).isEqualTo(reason);
            assertThat(run(server, "invalid", "invalid").path("reasonCode").asText()).as(pointer).isEqualTo(reason);
            target.assertNoRequest();
            JavaItScenario.observe("regression_suite.preflight", "ASSERTED", reason + ":" + pointer);
        }
    }

    static Stream<Arguments> invalidContracts() {
        return Stream.concat(invalidTriggers(), invalidVerification());
    }

    private static Stream<Arguments> invalidTriggers() {
        return Stream.of(
                Arguments.of("/metadata/execution/intent", "\"performance\"", "invalid_execution_intent"),
                Arguments.of("/metadata/execution/probeVerification", "\"false\"", "probe_verification_configuration_invalid"),
                Arguments.of("/metadata/execution/verificationBatchSize", "0", "verification_batch_size_invalid"),
                Arguments.of("/metadata/execution/verificationBatchSize", "1001", "verification_batch_size_invalid"),
                Arguments.of("/contract/targets", "[]", "target_missing"),
                Arguments.of("/contract/steps", "[]", "steps_missing"),
                Arguments.of("/contract/steps/0/order", "0", "step_order_duplicate"),
                Arguments.of("/contract/steps/0/order", "2", "step_order_non_sequential"),
                Arguments.of("/contract/steps/0/transport/http", "null", "transport_protocol_mismatch"),
                Arguments.of("/contract/steps/0/expect", "[]", "step_expectations_missing"),
                Arguments.of("/contract/steps/0/expect/0/operator", "\"unknown\"", "step_expectation_invalid"),
                Arguments.of("/contract/steps/0/when", "[]", "step_condition_malformed"),
                Arguments.of("/contract/steps/0/when", "{\"left\":\"invalid\",\"op\":\"exists\"}", "step_condition_path_missing"),
                Arguments.of("/contract/steps/0/when", "{\"left\":\"context.tenant\",\"op\":\"unknown\"}", "step_condition_operator_invalid"),
                Arguments.of("/contract/steps/0/when", "{\"left\":\"context.tenant\",\"op\":\"in\",\"right\":1}", "step_condition_type_mismatch"),
                Arguments.of("/contract/steps/0/extract", "{}", "step_extract_invalid"),
                Arguments.of("/contract/steps/0/transport/http/url", "\"${suite.absent}\"", "suite_context_forward_reference"));
    }

    private static Stream<Arguments> invalidVerification() {
        return Stream.of(
                Arguments.of("/contract/watchers", "{}", "watcher_configuration_invalid"),
                Arguments.of("/contract/watchers/0/dependency/stepOrder", "999", "watcher_dependency_invalid"),
                Arguments.of("/contract/watchers/0/provider/type", "\"kafka\"", "watcher_configuration_invalid"),
                Arguments.of("/contract/watchers/0/waitPolicy/timeoutMs", "-1", "watcher_wait_policy_invalid"),
                Arguments.of("/contract/watchers/0/waitPolicy/retryMax", "1.5", "watcher_wait_policy_invalid"),
                Arguments.of("/contract/watchers/0/expect", "[]", "watcher_expectation_invalid"),
                Arguments.of("/contract/externalVerification", "{}", "external_verification_configuration_invalid"),
                Arguments.of("/contract/externalVerification/0/id", "\"\"", "external_verification_id_invalid"),
                Arguments.of("/contract/externalVerification/0/provider/type", "\"kafka\"", "external_verification_request_invalid"),
                Arguments.of("/contract/externalVerification/0/expect", "[]", "external_verification_expectation_invalid"),
                Arguments.of("/contract/externalVerification/0/extract", "{}", "external_verification_extract_invalid"),
                Arguments.of("/contract/correlation", "\"enabled\"", "correlation_configuration_invalid"),
                Arguments.of("/contract/correlation", "{\"enabled\":true}", "correlation_configuration_invalid"));
    }

    @Test
    void forwardReferencesAndSecretSuitePromotionFailBeforeAnyHttpEffect() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "forward", "secret", "missing"));
            ObjectNode forward = generatedPlan(target.url("/forbidden"));
            step(forward, 0).putObject("when").put("left", "step[1].response.status").put("op", "equals").put("right", 200);
            save(server, "forward", forward);
            assertThat(run(server, "forward", "forward").path("reasonCode").asText()).isEqualTo("step_condition_forward_reference");
            ObjectNode secret = generatedPlan(target.url("/forbidden"));
            step(secret, 0).putArray("extract").addObject().put("from", "response.bodyJson.safe")
                    .put("as", "value").put("scope", "suite").put("secret", true);
            save(server, "secret", secret);
            assertThat(run(server, "secret", "secret").path("reasonCode").asText()).isEqualTo("suite_context_secret_forbidden");
            ObjectNode missing = generatedPlan(target.url("/forbidden"));
            contract(missing).putArray("prerequisites").addObject().put("key", "requiredValue")
                    .put("required", true).put("secret", false).put("provisioning", "user_input");
            save(server, "missing", missing);
            JsonNode result = run(server, "missing", "missing");
            assertThat(result.path("status").asText()).isEqualTo("needs_user_input");
            assertThat(result.path("reasonCode").asText()).isEqualTo("missing_prerequisites_user_input");
            assertThat(result.path("reasonMeta").path("missing")).extracting(JsonNode::asText).containsExactly("requiredValue");
            target.assertNoRequest();
            assertRun(server, workspace, "missing", "missing", "needs_user_input");
        }
    }

    @Test
    void failedAssertionAndRequiredExtractionStopOrderedTriggers() throws Exception {
        Path workspace = JavaItScenario.workspace();
        try (TransportHttpTarget target = TransportHttpTarget.start(); McpServerProcess server = start(workspace)) {
            saveContext(server, context(workspace, target.url(""), "assertion", "extraction", "skip"));
            for (String name : new String[] {"assertion", "extraction", "skip"}) {
                ObjectNode plan = generatedPlan(target.url("/" + name));
                ObjectNode second = step(plan, 0).deepCopy().put("order", 2).put("id", "must-not-run");
                contract(plan).withArray("steps").add(second);
                if (name.equals("assertion")) {
                    ((ObjectNode) step(plan, 0).path("expect").get(0)).put("expected", 201);
                } else if (name.equals("extraction")) {
                    step(plan, 0).putArray("extract").addObject().put("from", "response.bodyJson.absent")
                            .put("as", "missing").put("required", true);
                } else {
                    second.putObject("when").put("left", "context.tenant").put("op", "equals").put("right", "other");
                }
                save(server, name, plan);
                JsonNode result = run(server, name, name);
                String reason = name.equals("assertion") ? "step_expectation_failed" : "extract_path_missing";
                if (name.equals("skip")) {
                    assertThat(result.path("details").path("steps").get(1).path("status").asText()).isEqualTo("skipped");
                    assertThat(result.path("details").path("runStatus").asText()).isEqualTo("pass");
                } else {
                    assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
                    assertThat(result.path("details").path("steps")).hasSize(1);
                    assertRun(server, workspace, name, name, "fail");
                }
                assertThat(target.received().uri()).isEqualTo("/" + name);
                target.assertNoRequest();
            }
        }
    }
}
