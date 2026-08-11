package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.registry;

import java.util.Objects;

/**
 * Immutable registered Probe endpoint configuration.
 *
 * @param id stable Probe identifier
 * @param baseUrl configured Probe base URL
 */
public record ProbeRegistration(String id, String baseUrl) {

    /**
     * Normalizes required registration values.
     */
    public ProbeRegistration {
        id = requiredValue(id, "id");
        baseUrl = requiredValue(baseUrl, "baseUrl");
    }

    private static String requiredValue(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
