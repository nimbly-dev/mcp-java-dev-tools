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
            "field_equals", "field_exists", "field_matches_regex", "numeric_gte", "numeric_lte", "contains",
            "probe_line_hit", "outcome_status");
    private static final Set<String> VERIFICATION_EXPECTATION_OPERATORS = Set.of(
            "field_equals", "field_exists", "field_matches_regex", "numeric_gte", "numeric_lte", "contains",
            "outcome_status");
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
        RegressionSuiteResult verificationResult = validateSupportedVerification(metadata, contract);
        if (verificationResult != null) {
            return verificationResult;
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

    private RegressionSuiteResult validateSupportedVerification(JsonNode metadata, JsonNode contract) {
        JsonNode probe = metadata.path("execution").path("probeVerification");
        if (!probe.isMissingNode() && !probe.isBoolean()) {
            return blocked("probe_verification_configuration_invalid",
                    "set metadata.execution.probeVerification to true or false");
        }
        if (hasProbeExpectation(contract.path("steps")) && !probe.asBoolean(false)) {
            return blocked("probe_verification_configuration_invalid",
                    "enable metadata.execution.probeVerification for probe_line_hit expectations");
        }
        RegressionSuiteResult watcherResult = validateWatchers(contract.path("watchers"));
        if (watcherResult != null) {
            return watcherResult;
        }
        RegressionSuiteResult externalResult = validateExternalVerification(contract.path("externalVerification"));
        if (externalResult != null) {
            return externalResult;
        }
        JsonNode correlation = contract.path("correlation");
        if (configuredCorrelation(correlation)) {
            return validateCorrelation(correlation);
        }
        return null;
    }

    private boolean hasProbeExpectation(JsonNode steps) {
        for (JsonNode step : steps) {
            for (JsonNode expectation : step.path("expect")) {
                if ("probe_line_hit".equals(expectation.path("operator").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private RegressionSuiteResult validateWatchers(JsonNode watchers) {
        if (watchers.isMissingNode()) {
            return null;
        }
        if (!watchers.isArray()) {
            return blocked("watcher_configuration_invalid", "provide watchers as an array");
        }
        for (JsonNode watcher : watchers) {
            if (watcher.path("id").asText().isBlank()
                    || watcher.path("dependency").path("stepOrder").asInt(0) < 1
                    || !"http".equals(watcher.path("provider").path("type").asText())
                    || !watcher.path("provider").path("transport").path("request").isObject()) {
                return blocked("watcher_configuration_invalid",
                        "provide an id, completed-step dependency, and HTTP provider request");
            }
            RegressionSuiteResult expectations = validateVerificationExpectations(
                    watcher.path("expect"), "watcher_expectation_invalid");
            if (expectations != null) {
                return expectations;
            }
        }
        return null;
    }

    private RegressionSuiteResult validateExternalVerification(JsonNode verifications) {
        if (verifications.isMissingNode()) {
            return null;
        }
        if (!verifications.isArray()) {
            return blocked("external_verification_configuration_invalid",
                    "provide externalVerification as an array");
        }
        for (JsonNode verification : verifications) {
            if (verification.path("id").asText().isBlank()) {
                return blocked("external_verification_id_invalid", "provide a stable verification id");
            }
            String provider = verification.path("provider").path("type").asText();
            JsonNode request = verification.path("request").path(provider);
            if (!("http".equals(provider) || "sql".equals(provider)) || !request.isObject()) {
                return blocked("external_verification_request_invalid",
                        "pair an HTTP or SQL provider with its matching request block");
            }
            if ("sql".equals(provider)
                    && (request.path("connectionRef").asText().isBlank()
                            || request.path("statement").asText().isBlank()
                            || request.has("jdbcUrl") || request.has("username") || request.has("password"))) {
                return blocked("external_verification_request_invalid",
                        "use a connectionRef and parameterized SQL statement without inline connection details");
            }
            RegressionSuiteResult expectations = validateVerificationExpectations(
                    verification.path("expect"), "external_verification_expectation_invalid");
            if (expectations != null) {
                return expectations;
            }
            RegressionSuiteResult extractions = validateExtractions(verification.path("extract"));
            if (extractions != null) {
                return blocked("external_verification_extract_invalid",
                        "provide deterministic external verification extractions");
            }
        }
        return null;
    }

    private RegressionSuiteResult validateVerificationExpectations(JsonNode expectations, String reasonCode) {
        if (!expectations.isArray() || expectations.isEmpty()) {
            return blocked(reasonCode, "provide deterministic verification expectations");
        }
        for (JsonNode expectation : expectations) {
            String actualPath = expectation.path("actualPath").asText();
            String operator = expectation.path("operator").asText();
            if (expectation.path("id").asText().isBlank()
                    || !isObjectPath(actualPath)
                    || !VERIFICATION_EXPECTATION_OPERATORS.contains(operator)
                    || (!"field_exists".equals(operator) && !expectation.has("expected"))) {
                return blocked(reasonCode, "provide a deterministic object path and supported operator");
            }
        }
        return null;
    }

    private RegressionSuiteResult validateCorrelation(JsonNode correlation) {
        if (!correlation.isObject() || !correlation.path("enabled").isBoolean()) {
            return blocked("correlation_configuration_invalid", "provide correlation.enabled as a boolean");
        }
        if (!correlation.path("enabled").asBoolean()) {
            return null;
        }
        RegressionSuiteResult coreResult = validateCorrelationCore(correlation);
        return coreResult == null ? validateCorrelationRuntime(correlation) : coreResult;
    }

    private RegressionSuiteResult validateCorrelationCore(JsonNode correlation) {
        String sessionId = correlation.path("correlationSessionId").asText().trim();
        JsonNode key = correlation.path("key");
        String keyType = key.path("type").asText();
        String explicitValue = key.path("value").asText().trim();
        JsonNode source = key.path("source");
        boolean responseSource = "json_path".equals(source.path("type").asText())
                && isObjectPath(source.path("path").asText());
        long maxWindowMs = correlation.path("window").path("maxWindowMs").asLong(0L);
        if (sessionId.isEmpty()
                || !("traceId".equals(keyType) || "requestId".equals(keyType) || "messageId".equals(keyType))
                || explicitValue.isEmpty() && !responseSource
                || maxWindowMs < 1L
                || !nonEmptyStrings(correlation.path("probeIds"))
                || !nonEmptyStrings(correlation.path("expectedFlow"))) {
            return blocked("correlation_configuration_invalid",
                    "provide a session, key, window, probeIds, and expectedFlow");
        }
        return null;
    }

    private RegressionSuiteResult validateCorrelationRuntime(JsonNode correlation) {
        JsonNode runtime = correlation.path("runtimeEvidence");
        if (!runtime.isMissingNode() && !runtime.isObject()) {
            return blocked("correlation_runtime_configuration_invalid",
                    "provide runtimeEvidence as an object");
        }
        if (runtime.path("required").asBoolean(false)
                && (!runtime.path("eventKeyPath").asText().startsWith("$.")
                        || !nonEmptyStrings(runtime.path("probeIds")))) {
            return blocked("correlation_runtime_configuration_invalid",
                    "provide runtime probeIds and a $. eventKeyPath");
        }
        JsonNode runtimeInstanceIds = runtime.path("runtimeInstanceIds");
        if (!runtimeInstanceIds.isMissingNode()
                && (!runtimeInstanceIds.isArray()
                        || !runtimeInstanceIds.isEmpty() && !nonEmptyStrings(runtimeInstanceIds))) {
            return blocked("correlation_runtime_configuration_invalid",
                    "provide runtimeInstanceIds as an array of non-blank identifiers");
        }
        for (JsonNode boundary : correlation.path("consumerBoundaries")) {
            JsonNode selector = boundary.path("selector");
            if (boundary.path("id").asText().isBlank()
                    || selector.path("fqcn").asText().isBlank()
                    || selector.path("method").asText().isBlank()
                    || !selector.path("parameterTypes").isArray()
                    || !boundary.path("eventArgumentIndex").canConvertToInt()
                    || boundary.path("eventArgumentIndex").asInt() < 0) {
                return blocked("correlation_consumer_boundary_invalid",
                        "provide an exact consumer selector and event argument index");
            }
        }
        return null;
    }

    private boolean nonEmptyStrings(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            return false;
        }
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private boolean configuredCorrelation(JsonNode correlation) {
        if (correlation.isMissingNode()) {
            return false;
        }
        JsonNode enabled = correlation.path("enabled");
        return !correlation.isObject() || !enabled.isBoolean() || enabled.asBoolean();
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
