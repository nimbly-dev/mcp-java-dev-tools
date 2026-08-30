package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.Locale;
import java.util.Objects;

/** Stable lower-case identifier for one aggregate Core operation. */
public record OperationId(String value) implements Comparable<OperationId> {

    /** Creates and validates one canonical operation identifier. */
    public OperationId {
        Objects.requireNonNull(value, "operation id must not be null");
        if (!value.equals(value.trim()) || value.length() > 128
                || !value.matches("[a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)+")) {
            throw new IllegalArgumentException("operation id must be lower-case dot-separated text");
        }
    }

    /** @param value canonical identifier text @return validated identifier */
    public static OperationId of(String value) {
        return new OperationId(value);
    }

    /** Converts an existing Tool/action pair without changing that public pair. */
    public static OperationId fromLegacy(String toolName, String action) {
        Objects.requireNonNull(toolName, "tool name must not be null");
        Objects.requireNonNull(action, "action must not be null");
        return new OperationId(toolName.trim().toLowerCase(Locale.ROOT) + "."
                + action.trim().toLowerCase(Locale.ROOT).replace('/', '.'));
    }

    /** @return first identifier segment, used as the exact API filter */
    public String api() {
        int separator = value.indexOf('.');
        return separator < 0 ? value : value.substring(0, separator);
    }

    /** @return final identifier segment, used as the operation label */
    public String operation() {
        int separator = value.lastIndexOf('.');
        return separator < 0 ? value : value.substring(separator + 1);
    }

    @Override
    public int compareTo(OperationId other) {
        return value.compareTo(other.value);
    }
}
