package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.key;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Shared batch Strict Line Key selector used by later Probe actions.
 *
 * @param keys requested strict keys
 */
public record ProbeKeyBatchSelector(List<String> keys) {

    /**
     * Defensively copies the request list while preserving invalid values for
     * deterministic action-level validation.
     */
    public ProbeKeyBatchSelector {
        List<String> values = keys == null ? List.of() : new ArrayList<>(keys);
        keys = Collections.unmodifiableList(values);
    }

    /**
     * Resolves every key or returns empty when any key is invalid.
     *
     * @return normalized strict keys when every requested key is valid
     */
    public Optional<List<StrictLineKey>> resolveAll() {
        List<StrictLineKey> resolved = new ArrayList<>();
        for (String key : keys) {
            Optional<StrictLineKey> strictKey = StrictLineKey.parse(key);
            if (strictKey.isEmpty()) {
                return Optional.empty();
            }
            resolved.add(strictKey.get());
        }
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(resolved));
    }
}
