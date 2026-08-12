package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import java.util.List;
import java.util.Objects;

/**
 * Bounded helper command request.
 *
 * @param operation public lifecycle operation
 * @param arguments exact helper arguments after the operation
 */
public record JvmLifecycleHelperRequest(String operation, List<String> arguments) {

    /** Validates and copies helper arguments. */
    public JvmLifecycleHelperRequest {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(arguments, "arguments must not be null");
        arguments = List.copyOf(arguments);
    }
}
