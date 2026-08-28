package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.model.result;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic, transport-neutral Security Suite result. */
public record SecuritySuiteResult(
        String status,
        String reasonCode,
        String nextAction,
        Map<String, Object> reasonMeta,
        Map<String, Object> details) {

    /** Makes result maps immutable and supplies safe fallback fields. */
    public SecuritySuiteResult {
        status = status == null ? "blocked" : status;
        reasonCode = reasonCode == null ? "security_suite_internal_error" : reasonCode;
        reasonMeta = copy(reasonMeta);
        details = copy(details);
    }

    /** Creates a completed Security plan result. */
    public static SecuritySuiteResult completed(Map<String, Object> details) {
        return new SecuritySuiteResult("completed", "ok", null, Map.of(), details);
    }

    /** Creates a deterministic Fail-Closed result. */
    public static SecuritySuiteResult blocked(String reasonCode, String nextAction, Map<String, Object> reasonMeta) {
        return new SecuritySuiteResult("blocked", reasonCode, nextAction, reasonMeta, Map.of());
    }

    private static Map<String, Object> copy(Map<String, Object> values) {
        return values == null || values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
