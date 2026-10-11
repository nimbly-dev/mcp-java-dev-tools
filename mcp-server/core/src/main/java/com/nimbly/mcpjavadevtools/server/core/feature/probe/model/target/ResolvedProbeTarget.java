package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target;

import java.util.Objects;

/**
 * Successful Probe target resolution.
 *
 * @param target selected Probe target
 */
public record ResolvedProbeTarget(ProbeTarget target) implements ProbeTargetResolution {

    /**
     * Rejects an absent target for a resolved outcome.
     */
    public ResolvedProbeTarget {
        Objects.requireNonNull(target, "target must not be null");
    }
}
