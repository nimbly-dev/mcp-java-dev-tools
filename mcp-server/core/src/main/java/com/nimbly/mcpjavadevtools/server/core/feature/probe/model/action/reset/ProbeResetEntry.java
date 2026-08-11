package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.reset;

import java.util.Objects;

/**
 * One bounded input-associated or class-discovered reset outcome.
 */
public record ProbeResetEntry(
        String key,
        Boolean reset,
        Integer httpStatus,
        Boolean lineResolvable,
        String lineValidation) {

    /**
     * Rejects absent per-key association values.
     */
    public ProbeResetEntry {
        Objects.requireNonNull(key, "key must not be null");
    }
}
