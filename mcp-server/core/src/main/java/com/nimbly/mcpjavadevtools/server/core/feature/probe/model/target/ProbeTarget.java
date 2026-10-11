package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target;

import java.net.URI;
import java.util.Objects;

/**
 * Resolved Probe runtime target.
 *
 * @param probeId resolved Probe identifier when registry-backed
 * @param baseUrl normalized absolute HTTP(S) base URL
 * @param source selection origin
 */
public record ProbeTarget(String probeId, URI baseUrl, ProbeTargetSource source) {

    /**
     * Validates the values used by later endpoint operations.
     */
    public ProbeTarget {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
}
