package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action;

import java.util.Arrays;
import java.util.Optional;

/**
 * Complete public action allowlist for the JVM lifecycle MCP Tool.
 */
public enum JvmLifecycleAction {
    LIST_JVMS("list_jvms"),
    ATTACH("attach"),
    DEACTIVATE("deactivate");

    private final String value;

    JvmLifecycleAction(String value) {
        this.value = value;
    }

    /**
     * Returns the stable MCP action value.
     *
     * @return action value
     */
    public String value() {
        return value;
    }

    /**
     * Resolves one public action value.
     *
     * @param value public value
     * @return matching action when supported
     */
    public static Optional<JvmLifecycleAction> fromValue(String value) {
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}
