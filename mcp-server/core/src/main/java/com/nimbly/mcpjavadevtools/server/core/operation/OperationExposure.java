package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable description of one Application MCP registration boundary. */
public record OperationExposure(String toolName, String adapterType, List<String> actions) {

    /** Validates and defensively copies the advertised registration. */
    public OperationExposure {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(adapterType, "adapterType must not be null");
        Objects.requireNonNull(actions, "actions must not be null");
        if (toolName.isBlank() || adapterType.isBlank() || actions.isEmpty()) {
            throw new IllegalArgumentException("operation exposure values must not be blank");
        }
        actions = List.copyOf(actions);
        if (actions.stream().anyMatch(action -> action == null || action.isBlank())) {
            throw new IllegalArgumentException("operation exposure actions must not be blank");
        }
        if (actions.size() != new HashSet<>(actions).size()) {
            throw new IllegalArgumentException("operation exposure actions must be unique");
        }
    }
}
