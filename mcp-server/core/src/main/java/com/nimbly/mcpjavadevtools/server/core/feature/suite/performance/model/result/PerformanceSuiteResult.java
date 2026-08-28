package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, transport-neutral Performance Suite result. */
public record PerformanceSuiteResult(
        String status,
        String reasonCode,
        String nextAction,
        Map<String, Object> reasonMeta,
        Map<String, Object> details) {

    /** Makes result maps immutable and supplies safe fallback fields. */
    public PerformanceSuiteResult {
        status = status == null ? "blocked" : status;
        reasonCode = reasonCode == null ? "performance_suite_internal_error" : reasonCode;
        reasonMeta = copy(reasonMeta);
        details = copy(details);
    }

    /** Creates a successful run result. */
    public static PerformanceSuiteResult completed(Map<String, Object> details) {
        return new PerformanceSuiteResult("completed", "ok", null, Map.of(), details);
    }

    /** Creates a deterministic Fail-Closed outcome. */
    public static PerformanceSuiteResult blocked(String reasonCode, String nextAction, Map<String, Object> reasonMeta) {
        return new PerformanceSuiteResult("blocked", reasonCode, nextAction, reasonMeta, Map.of());
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
