package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.action;

import java.util.Arrays;
import java.util.Optional;

/** Closed public action allowlist for the failure_analysis MCP Tool. */
public enum FailureAnalysisAction {

    ANALYZE_TRACE("analyze_trace"),
    VERIFY_REPRODUCTION("verify_reproduction");

    private final String value;

    FailureAnalysisAction(String value) {
        this.value = value;
    }

    /** @return stable MCP action value */
    public String value() {
        return value;
    }

    /** @param value MCP action value @return matching action when supported */
    public static Optional<FailureAnalysisAction> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}
