package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, transport-neutral Regression Suite result. */
public record RegressionSuiteResult(
        String status,
        String reasonCode,
        String nextAction,
        Map<String, Object> reasonMeta,
        Map<String, Object> details) {

    /** Makes all result maps immutable and prevents null result fields. */
    public RegressionSuiteResult {
        status = status == null ? "blocked" : status;
        reasonCode = reasonCode == null ? "regression_suite_internal_error" : reasonCode;
        reasonMeta = copy(reasonMeta);
        details = copy(details);
    }

    /** Creates a ready preflight result. */
    public static RegressionSuiteResult ready(Map<String, Object> details) {
        return new RegressionSuiteResult("ready", "ok", null, Map.of(), details);
    }

    /** Creates a deterministic preflight outcome that needs operator-supplied context. */
    public static RegressionSuiteResult needsUserInput(
            String reasonCode,
            String nextAction,
            Map<String, Object> reasonMeta) {
        return new RegressionSuiteResult("needs_user_input", reasonCode, nextAction, reasonMeta, Map.of());
    }

    /** Creates a deterministic preflight outcome that requires allowed discovery. */
    public static RegressionSuiteResult needsDiscovery(
            String reasonCode,
            String nextAction,
            Map<String, Object> reasonMeta) {
        return new RegressionSuiteResult("needs_discovery", reasonCode, nextAction, reasonMeta, Map.of());
    }

    /** Creates a stale-plan outcome that preserves the TypeScript compatibility status. */
    public static RegressionSuiteResult stalePlan(
            String reasonCode,
            String nextAction,
            Map<String, Object> reasonMeta) {
        return new RegressionSuiteResult("stale_plan", reasonCode, nextAction, reasonMeta, Map.of());
    }

    /** Creates a deterministic Fail-Closed outcome. */
    public static RegressionSuiteResult blocked(
            String reasonCode,
            String nextAction,
            Map<String, Object> reasonMeta) {
        return new RegressionSuiteResult("blocked_invalid", reasonCode, nextAction, reasonMeta, Map.of());
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
