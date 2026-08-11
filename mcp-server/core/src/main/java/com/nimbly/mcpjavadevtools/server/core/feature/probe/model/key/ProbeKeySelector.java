package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key;

import java.util.Optional;

/**
 * Shared single-key selector used by later Probe actions.
 *
 * @param key strict key or method key
 * @param lineHint optional line hint for a method key
 */
public record ProbeKeySelector(String key, Integer lineHint) {

    /**
     * Resolves this selector without throwing for invalid key input.
     *
     * @return normalized Strict Line Key when valid
     */
    public Optional<StrictLineKey> resolve() {
        return StrictLineKey.resolve(key, lineHint);
    }
}
