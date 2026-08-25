package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates executable Regression-plan invariants before external work begins. */
public final class RegressionPlanPreflight {

    private static final String STRICT_LINE_KEY_PATTERN = "[\\w.$]+#[\\w$]+:\\d+";
    private static final Pattern OBJECT_PATH_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_-]*(\\[\\d+])*(\\.[A-Za-z_][A-Za-z0-9_-]*(\\[\\d+])*)*");
    private static final Pattern STEP_PATH_PATTERN = Pattern.compile(
            "step\\[(\\d+)]\\.([A-Za-z_][A-Za-z0-9_-]*(\\[\\d+])*(\\.[A-Za-z_][A-Za-z0-9_-]*(\\[\\d+])*)*)");
    private static final Set<String> EXPECTATION_OPERATORS = Set.of(
            "field_equals", "field_exists", "field_matches_regex", "numeric_gte", "numeric_lte", "contains");
    private static final Set<String> CONDITION_OPERATORS = Set.of("equals", "not_equals", "in", "exists");

    /** Validates a metadata/contract plan envelope without persisting supplied context. */
    public RegressionSuiteResult validate(JsonNode input) {
        if (input == null || !input.isObject()) {
            return blocked("regression_plan_input_invalid", "provide a metadata and contract object");
        }
        JsonNode metadata = input.path("metadata");
        JsonNode contract = input.path("contract");
        if (!metadata.isObject() || !contract.isObject()) {
            return blocked("regression_plan_input_invalid", "provide a metadata and contract object");
        }
        RegressionSuiteResult metadataResult = validateMetadata(metadata);
        if (metadataResult != null) {
            return metadataResult;
        }
        RegressionSuiteResult targetResult = validateTargets(contract.path("targets"));
        if (targetResult != null) {
            return targetResult;
        }
        RegressionSuiteResult stepResult = validateSteps(contract.path("steps"));
        if (stepResult != null) {
            return stepResult;
        }
        RegressionSuiteResult probeResult = validatePinnedProbeKeys(metadata, contract.path("targets"));
        if (probeResult != null) {
            return probeResult;
        }
        RegressionSuiteResult prerequisiteResult = validatePrerequisites(input, metadata, contract.path("prerequisites"));
        if (prerequisiteResult != null) {
            return prerequisiteResult;
        }
        return RegressionSuiteResult.ready(Map.of(
                "targetCount", contract.path("targets").size(),
                "stepCount", contract.path("steps").size(),
                "checks", List.of("metadata", "targets", "steps", "pinned_probe_keys")));
    }

    private RegressionSuiteResult validateMetadata(JsonNode metadata) {
        if (!"regression".equals(metadata.path("execution").path("intent").asText())) {
            return blocked("invalid_execution_intent", "set metadata.execution.intent to regression");
        }
        return null;
    }

    private RegressionSuiteResult validateTargets(JsonNode targets) {
        if (!targets.isArray() || targets.isEmpty()) {
            return blocked("target_missing", "add at least one contract.targets entry");
        }
        return null;
    }

    private RegressionSuiteResult validateSteps(JsonNode steps) {
        if (!steps.isArray() || steps.isEmpty()) {
            return blocked("steps_missing", "add at least one contract.steps entry");
        }
        Set<Integer> orders = new HashSet<>();
        for (JsonNode step : steps) {
            RegressionSuiteResult result = validateStep(step, orders);
            if (result != null) {
                return result;
            }
        }
        return validateSequentialOrders(orders);
    }

    private RegressionSuiteResult validateStep(JsonNode step, Set<Integer> orders) {
        int order = step.path("order").asInt(0);
        if (order < 1 || !orders.add(order)) {
            return blocked("step_order_duplicate", "ensure each step.order value is unique and positive");
        }
        String protocol = step.path("protocol").asText();
        if (!"http".equals(protocol) || !step.path("transport").path(protocol).isObject()) {
            return blocked("transport_protocol_mismatch", "provide transport.http for every HTTP step");
        }
        JsonNode expectations = step.path("expect");
        if (!expectations.isArray() || expectations.isEmpty()) {
            return blocked("step_expectations_missing", "add deterministic steps[].expect entries");
        }
        RegressionSuiteResult expectationResult = validateExpectations(expectations);
        if (expectationResult != null) {
            return expectationResult;
        }
        RegressionSuiteResult conditionResult = validateCondition(step.path("when"), order);
        if (conditionResult != null) {
            return conditionResult;
        }
        return validateExtractions(step.path("extract"));
    }

    private RegressionSuiteResult validateSequentialOrders(Set<Integer> orders) {
        for (int expected = 1; expected <= orders.size(); expected++) {
            if (!orders.contains(expected)) {
                return blocked("step_order_non_sequential", "number step.order sequentially from 1 through N");
            }
        }
        return null;
    }

    private RegressionSuiteResult validateExpectations(JsonNode expectations) {
        for (JsonNode expectation : expectations) {
            String actualPath = expectation.path("actualPath").asText();
            String operator = expectation.path("operator").asText();
            if (!expectation.path("id").isTextual()
                    || expectation.path("id").asText().isBlank()
                    || !isObjectPath(actualPath)
                    || !EXPECTATION_OPERATORS.contains(operator)
                    || (!"field_exists".equals(operator) && !expectation.has("expected"))) {
                return blocked("step_expectation_invalid", "provide id, actualPath, and operator for each expectation");
            }
        }
        return null;
    }

    private RegressionSuiteResult validateCondition(JsonNode condition, int stepOrder) {
        if (condition.isMissingNode() || condition.isNull()) {
            return null;
        }
        if (!condition.isObject()) {
            return blocked("step_condition_malformed", "provide a deterministic steps[].when condition object");
        }
        if (condition.has("not")) {
            return validateCondition(condition.path("not"), stepOrder);
        }
        if (condition.has("all") || condition.has("any")) {
            JsonNode children = condition.has("all") ? condition.path("all") : condition.path("any");
            if (!children.isArray() || children.isEmpty()) {
                return blocked("step_condition_malformed", "provide a non-empty all or any condition array");
            }
            for (JsonNode child : children) {
                RegressionSuiteResult result = validateCondition(child, stepOrder);
                if (result != null) {
                    return result;
                }
            }
            return null;
        }
        String left = condition.path("left").asText();
        String operator = condition.path("op").asText();
        if (!isConditionPath(left)) {
            return blocked("step_condition_path_missing", "use context.<path> or a prior step[n].<path>");
        }
        RegressionSuiteResult referenceResult = validateStepReference(left, stepOrder);
        if (referenceResult != null) {
            return referenceResult;
        }
        if (!CONDITION_OPERATORS.contains(operator)) {
            return blocked("step_condition_operator_invalid", "use a supported steps[].when operator");
        }
        if (!"exists".equals(operator) && !condition.has("right")) {
            return blocked("step_condition_type_mismatch", "provide right for this steps[].when operator");
        }
        if ("in".equals(operator) && !condition.path("right").isArray()) {
            return blocked("step_condition_type_mismatch", "provide an array right value for the in operator");
        }
        return null;
    }

    private RegressionSuiteResult validateStepReference(String left, int stepOrder) {
        Matcher matcher = STEP_PATH_PATTERN.matcher(left);
        if (matcher.matches() && Integer.parseInt(matcher.group(1)) >= stepOrder) {
            return blocked("step_condition_forward_reference", "reference only a completed prior step");
        }
        return null;
    }

    private boolean isConditionPath(String path) {
        return path.startsWith("context.") && isObjectPath(path.substring("context.".length()))
                || STEP_PATH_PATTERN.matcher(path).matches();
    }

    private boolean isObjectPath(String path) {
        return OBJECT_PATH_PATTERN.matcher(path).matches();
    }

    private RegressionSuiteResult validateExtractions(JsonNode extractions) {
        if (extractions.isMissingNode() || extractions.isNull()) {
            return null;
        }
        if (!extractions.isArray()) {
            return blocked("step_extract_invalid", "provide steps[].extract as an array");
        }
        for (JsonNode extraction : extractions) {
            String from = extraction.path("from").asText();
            String name = extraction.path("as").asText();
            String scope = extraction.path("scope").asText("plan");
            if (!isObjectPath(from)
                    || name.isBlank()
                    || !("plan".equals(scope) || "suite".equals(scope))) {
                return blocked("step_extract_invalid", "provide deterministic from, as, and scope extraction values");
            }
            if ("suite".equals(scope) && extraction.path("secret").asBoolean()) {
                return blocked("suite_context_secret_forbidden", "do not persist secret values in suite context");
            }
        }
        return null;
    }

    private RegressionSuiteResult validatePinnedProbeKeys(JsonNode metadata, JsonNode targets) {
        JsonNode execution = metadata.path("execution");
        if (!execution.path("probeVerification").asBoolean() || !execution.path("pinStrictProbeKey").asBoolean()) {
            return null;
        }
        for (JsonNode target : targets) {
            String key = target.path("runtimeVerification").path("strictProbeKey").asText();
            if (!key.matches(STRICT_LINE_KEY_PATTERN)) {
                return RegressionSuiteResult.stalePlan(
                        "strict_probe_key_invalid",
                        "set runtimeVerification.strictProbeKey to Class#method:line",
                        Map.of("failedStep", "preflight"));
            }
        }
        return null;
    }

    private RegressionSuiteResult validatePrerequisites(
            JsonNode input,
            JsonNode metadata,
            JsonNode prerequisites) {
        if (!prerequisites.isArray() || prerequisites.isEmpty()) {
            return null;
        }
        JsonNode providedContext = input.path("providedContext");
        List<String> userInput = new ArrayList<>();
        List<String> discoverable = new ArrayList<>();
        for (JsonNode prerequisite : prerequisites) {
            classifyPrerequisite(prerequisite, providedContext, userInput, discoverable);
        }
        if (userInput.isEmpty() && discoverable.isEmpty()) {
            return null;
        }
        if (!discoverable.isEmpty() && !allowsDiscovery(metadata)) {
            return blocked("discoverable_prerequisite_policy_disabled",
                    "enable discoverable-prerequisite policy or provide the required context");
        }
        return missingPrerequisiteResult(userInput, discoverable);
    }

    private void classifyPrerequisite(
            JsonNode prerequisite,
            JsonNode providedContext,
            List<String> userInput,
            List<String> discoverable) {
        String key = prerequisite.path("key").asText().trim();
        if (key.isEmpty() || !prerequisite.path("required").asBoolean()) {
            return;
        }
        if (providedContext.isObject() && providedContext.hasNonNull(key)) {
            return;
        }
        if (prerequisite.hasNonNull("default")) {
            return;
        }
        if ("discoverable".equals(prerequisite.path("provisioning").asText())) {
            discoverable.add(key);
            return;
        }
        userInput.add(key);
    }

    private boolean allowsDiscovery(JsonNode metadata) {
        return "allow_discoverable_prerequisites".equals(
                metadata.path("execution").path("discoveryPolicy").asText());
    }

    private RegressionSuiteResult missingPrerequisiteResult(
            List<String> userInput,
            List<String> discoverable) {
        Map<String, Object> details = Map.of(
                "missing", List.copyOf(userInput),
                "discoverablePending", List.copyOf(discoverable));
        if (!userInput.isEmpty() && !discoverable.isEmpty()) {
            return RegressionSuiteResult.needsUserInput(
                    "missing_prerequisites_mixed",
                    "provide missing context values before continuing discovery",
                    details);
        }
        if (!userInput.isEmpty()) {
            return RegressionSuiteResult.needsUserInput(
                    "missing_prerequisites_user_input",
                    "provide the missing required context values",
                    details);
        }
        return RegressionSuiteResult.needsDiscovery(
                "missing_prerequisites_discoverable",
                "run the allowed prerequisite discovery before executing the plan",
                details);
    }

    private RegressionSuiteResult blocked(String reasonCode, String nextAction) {
        return RegressionSuiteResult.blocked(reasonCode, nextAction, Map.of("failedStep", "preflight"));
    }
}
