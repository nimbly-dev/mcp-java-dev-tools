package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation.RegressionCrossPlanCorrelation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Applies the existing scalar allowlist to restored, promoted and persisted suite context. */
public class ExecutionSuiteContext {
    private static final Pattern CORRELATION_CONTEXT_KEY = Pattern.compile(
            "suite\\.correlation\\.(?:last|[A-Za-z0-9_.-]+)"
                    + "\\.(?:keyValue|keyType|correlationSessionId|sourcePlanName)");
    private static final Set<String> PROMOTABLE_CONTEXT_KEYS = Set.of(
            "traceid", "requestid", "messageid", "correlationid", "tenantid", "resourceid", "runlabel",
            "environmentname", "runtimecontextname");
    private static final int MAX_CONTEXT_VALUE_LENGTH = 256;
    private final RegressionCrossPlanCorrelation crossPlanCorrelation;

    public ExecutionSuiteContext(RegressionCrossPlanCorrelation crossPlanCorrelation) {
        this.crossPlanCorrelation = crossPlanCorrelation;
    }

    /** Promotes only a terminal successful outcome selected by the orchestration flow. */
    public void promoteSuccessful(Map<String, Object> suiteContext, JsonNode plan, Map<String, Object> outcome) {
        suiteContext.putAll(safeContext(plan.path("providedContext")));
        Object details = outcome.get("details");
        if (details instanceof Map<?, ?> map) {
            Object promoted = map.get("suiteContext");
            if (promoted instanceof Map<?, ?> values) {
                suiteContext.putAll(safeContext(values));
            }
        }
        crossPlanCorrelation.promote(
                suiteContext, plan.path("planName").asText(), outcome);
    }

    public static Map<String, Object> redactOutcomeContext(Map<String, Object> outcome) {
        if (!(outcome.get("details") instanceof Map<?, ?> values)) {
            return outcome;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        values.forEach((key, value) -> details.put(String.valueOf(key), value));
        if (details.containsKey("suiteContext")) {
            details.put("suiteContext", safeContext(details.get("suiteContext")));
        }
        Map<String, Object> redacted = new LinkedHashMap<>(outcome);
        redacted.put("details", Map.copyOf(details));
        return Map.copyOf(redacted);
    }

    public static Map<String, Object> safeContext(Object value) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (value instanceof JsonNode node && node.isObject()) {
            node.fields().forEachRemaining(entry -> addSafeContext(safe, entry.getKey(), nodeValue(entry.getValue())));
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> addSafeContext(safe, String.valueOf(key), entry));
        }
        return safe;
    }

    private static void addSafeContext(Map<String, Object> safe, String key, Object value) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if ((PROMOTABLE_CONTEXT_KEYS.contains(normalized) || CORRELATION_CONTEXT_KEY.matcher(key).matches()
                || key.matches("suite\\.[A-Za-z_][A-Za-z0-9_.-]{0,125}"))
                && safeValue(value)) {
            safe.put(key, value);
        }
    }

    private static Object nodeValue(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value;
        }
        return null;
    }

    private static boolean safeValue(Object value) {
        if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
            return false;
        }
        return !(value instanceof String text) || text.length() <= MAX_CONTEXT_VALUE_LENGTH;
    }

}
