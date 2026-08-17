package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result;

/** Stable outcome vocabulary used by the Failure Analysis compatibility surface. */
public enum FailureAnalysisOutcome {

    ANALYZED("ANALYZED", "analyzed"),
    REPRODUCED("REPRODUCED", "reproduced"),
    NOT_REPRODUCED("NOT_REPRODUCED", "not_reproduced"),
    INCONCLUSIVE("INCONCLUSIVE", "inconclusive"),
    BLOCKED_SIDECAR_UNAVAILABLE("BLOCKED_SIDECAR_UNAVAILABLE", "blocked"),
    BLOCKED_AMBIGUOUS_JVM("BLOCKED_AMBIGUOUS_JVM", "blocked"),
    BLOCKED_MISSING_AUTH("BLOCKED_MISSING_AUTH", "blocked"),
    BLOCKED_MISSING_TRIGGER("BLOCKED_MISSING_TRIGGER", "blocked"),
    BLOCKED_USER_ACTION_REQUIRED("BLOCKED_USER_ACTION_REQUIRED", "blocked"),
    BLOCKED_UNSAFE_OPERATION("BLOCKED_UNSAFE_OPERATION", "blocked"),
    ENVIRONMENT_MISMATCH("ENVIRONMENT_MISMATCH", "environment_mismatch"),
    CANCELLED("CANCELLED", "cancelled");

    private final String value;
    private final String status;

    FailureAnalysisOutcome(String value, String status) {
        this.value = value;
        this.status = status;
    }

    /** @return stable structured output value */
    public String value() {
        return value;
    }

    /** @return shared action response status */
    public String status() {
        return status;
    }
}
