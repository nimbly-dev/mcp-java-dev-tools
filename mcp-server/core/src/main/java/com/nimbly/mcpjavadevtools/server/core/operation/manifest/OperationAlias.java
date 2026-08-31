package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import java.util.Objects;

/** Released Tool/action identity retained as a compatibility alias. */
public record OperationAlias(String toolName, String action, boolean actionless)
        implements Comparable<OperationAlias> {

    /** Preserves the actionful alias constructor used by existing catalogs. */
    public OperationAlias(String toolName, String action) {
        this(toolName, action, false);
    }

    /** Validates one stable alias. */
    public OperationAlias {
        Objects.requireNonNull(toolName, "alias tool name must not be null");
        Objects.requireNonNull(action, "alias action must not be null");
        if (toolName.isBlank() || (!actionless && action.isBlank())
                || (actionless && !action.isBlank())
                || toolName.length() > 128 || action.length() > 128) {
            throw new IllegalArgumentException("operation alias values are outside the supported bounds");
        }
    }

    /** @return deterministic map key for duplicate detection */
    public String key() {
        return toolName + "\u001f" + action;
    }

    @Override
    public int compareTo(OperationAlias other) {
        return key().compareTo(other.key());
    }
}
