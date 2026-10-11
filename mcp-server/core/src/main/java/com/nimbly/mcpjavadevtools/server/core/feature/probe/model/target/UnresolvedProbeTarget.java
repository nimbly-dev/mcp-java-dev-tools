package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResultStatus;

import java.util.Objects;

/**
 * Expected target-resolution failure or blocked outcome.
 *
 * @param result deterministic unsuccessful result
 */
public record UnresolvedProbeTarget(ProbeResult result) implements ProbeTargetResolution {

    /**
     * Ensures target resolution cannot represent a success without a target.
     */
    public UnresolvedProbeTarget {
        Objects.requireNonNull(result, "result must not be null");
        if (result.status() == ProbeResultStatus.SUCCESS) {
            throw new IllegalArgumentException("unresolved target result must not be successful");
        }
    }
}
