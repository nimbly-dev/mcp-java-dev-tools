package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.check;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeRuntimeHints;
import java.util.List;
import java.util.Objects;

/**
 * Typed, safe output from the Probe check action.
 *
 * @param reset reset endpoint diagnostic result
 * @param status status endpoint diagnostic result
 * @param runtime safe runtime hints from status when available
 * @param recommendations bounded deterministic operator recommendations
 */
public record ProbeCheckResult(
        ProbeCheckEndpointResult reset,
        ProbeCheckEndpointResult status,
        ProbeRuntimeHints runtime,
        List<String> recommendations) implements ProbeActionResult {

    /**
     * Validates required diagnostics and copies recommendations.
     */
    public ProbeCheckResult {
        Objects.requireNonNull(reset, "reset must not be null");
        Objects.requireNonNull(status, "status must not be null");
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
    }

    /**
     * Returns whether reset, status, and key decoding were all confirmed.
     *
     * @return whether the target passed the complete diagnostic
     */
    public boolean healthy() {
        return reset.available()
                && status.available()
                && "mcp.jvm.diagnose#key".equals(status.responseKey());
    }
}
