package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result;

/**
 * Core result status mapped to the stable MCP status values.
 */
public enum JvmLifecycleResultStatus {
    OK("ok"),
    BLOCKED("blocked");

    private final String value;

    JvmLifecycleResultStatus(String value) {
        this.value = value;
    }

    /**
     * Returns the public status value.
     *
     * @return status value
     */
    public String value() {
        return value;
    }
}
