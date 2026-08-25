package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.PreflightRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl.ExecuteRegressionPlanAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefaultRegressionSuiteFeatureTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RegressionPlanPreflight preflight = new RegressionPlanPreflight();
    private final TransportExecutionFeature transport = request -> ExecuteTransportResult.httpResponse(
            "ok", "http", 200, java.util.Map.of(), "", 1);
    private final RegressionSuiteFeature feature = new DefaultRegressionSuiteFeature(
            List.of(
                    new PreflightRegressionPlanAction(preflight),
                    new ExecuteRegressionPlanAction(new RegressionPlanExecutor(preflight, transport, mapper))));

    @Test
    void acceptsAReadyRegressionPlanWithoutExposingProvidedContext() {
        ObjectNode input = validPlan();
        input.putObject("providedContext").put("authorization", "sensitive-value");

        RegressionSuiteResult result = feature.execute(request(input));

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.reasonCode()).isEqualTo("ok");
        assertThat(result.details()).containsEntry("targetCount", 1).containsEntry("stepCount", 1);
        assertThat(result.details().toString()).doesNotContain("sensitive-value");
    }

    @Test
    void rejectsAPlanWithMissingStepExpectations() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("contract").path("steps").get(0)).remove("expect");

        RegressionSuiteResult result = feature.execute(request(input));

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("step_expectations_missing");
        assertThat(result.reasonMeta()).containsEntry("failedStep", "preflight");
    }

    @Test
    void rejectsAPlanWithNonSequentialStepOrders() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("contract").path("steps").get(0)).put("order", 2);

        RegressionSuiteResult result = feature.execute(request(input));

        assertThat(result.reasonCode()).isEqualTo("step_order_non_sequential");
    }

    @Test
    void rejectsAnInvalidPinnedStrictLineKey() {
        ObjectNode input = validPlan();
        ((ObjectNode) input.path("contract").path("targets").get(0))
                .putObject("runtimeVerification").put("strictProbeKey", "not-a-strict-key");

        RegressionSuiteResult result = feature.execute(request(input));

        assertThat(result.status()).isEqualTo("stale_plan");
        assertThat(result.reasonCode()).isEqualTo("strict_probe_key_invalid");
    }

    @Test
    void rejectsARequestWithoutAnAction() {
        RegressionSuiteResult result = feature.execute(new RegressionSuiteRequest(null, validPlan()));

        assertThat(result.status()).isEqualTo("blocked_invalid");
        assertThat(result.reasonCode()).isEqualTo("regression_suite_request_invalid");
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

        RegressionSuiteResult result = feature.execute(request(input));

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

        RegressionSuiteResult result = feature.execute(request(input));

        assertThat(result.status()).isEqualTo("needs_discovery");
        assertThat(result.reasonCode()).isEqualTo("missing_prerequisites_discoverable");
        assertThat(result.reasonMeta()).containsEntry("discoverablePending", List.of("routeToken"));
    }

    @Test
    void executesOrderedHttpStepsThroughThePublicTransportFeature() {
        RegressionSuiteResult result = feature.execute(new RegressionSuiteRequest(
                RegressionSuiteAction.EXECUTE_PLAN, validPlan()));

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.details()).containsEntry("runStatus", "pass");
    }

    @Test
    void synthesizesCanonicalHttpUrlFromApiBaseUrlAndPath() {
        AtomicReference<Map<String, Object>> request = new AtomicReference<>();
        TransportExecutionFeature recording = input -> {
            request.set(((ExecuteTransportRequest) input).request());
            return ExecuteTransportResult.httpResponse("ok", "http", 200, Map.of(), "", 1);
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
                "ok", "http", 200, Map.of("X-Result", "accepted"), "{\"id\":\"order-7\"}", 1);

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
                "ok", "http", 200, Map.of(), "not-a-number", 1);

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
                    "ok", "http", 200, Map.of(), "{\"records\":[{\"type\":\"primary\"}]}", 1);
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
        RegressionSuiteResult operatorResult = feature.execute(request(invalidOperator));

        ObjectNode forwardReference = validPlan();
        ((ObjectNode) forwardReference.path("contract").path("steps").get(0)).putObject("when")
                .put("left", "step[1].response.status")
                .put("op", "equals")
                .put("right", 200);
        RegressionSuiteResult conditionResult = feature.execute(request(forwardReference));

        ObjectNode invalidExtraction = validPlan();
        ((ObjectNode) invalidExtraction.path("contract").path("steps").get(0)).putArray("extract")
                .addObject().put("from", "response.status").put("as", "status").put("scope", "invalid");
        RegressionSuiteResult extractionResult = feature.execute(request(invalidExtraction));

        assertThat(operatorResult.reasonCode()).isEqualTo("step_expectation_invalid");
        assertThat(conditionResult.reasonCode()).isEqualTo("step_condition_forward_reference");
        assertThat(extractionResult.reasonCode()).isEqualTo("step_extract_invalid");
    }

    private RegressionSuiteResult execute(TransportExecutionFeature transportFeature, ObjectNode input) {
        RegressionSuiteFeature configured = new DefaultRegressionSuiteFeature(List.of(
                new PreflightRegressionPlanAction(preflight),
                new ExecuteRegressionPlanAction(new RegressionPlanExecutor(preflight, transportFeature, mapper))));
        return configured.execute(new RegressionSuiteRequest(RegressionSuiteAction.EXECUTE_PLAN, input));
    }

    private RegressionSuiteRequest request(ObjectNode input) {
        return new RegressionSuiteRequest(RegressionSuiteAction.PREFLIGHT, input);
    }

    private ObjectNode validPlan() {
        ObjectNode input = mapper.createObjectNode();
        input.putObject("providedContext").put("apiBaseUrl", "https://example.test");
        input.putObject("metadata").putObject("execution")
                .put("intent", "regression")
                .put("probeVerification", true)
                .put("pinStrictProbeKey", true);
        ObjectNode contract = input.putObject("contract");
        contract.putArray("targets").addObject()
                .putObject("runtimeVerification").put("strictProbeKey", "example.Health#check:12");
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
