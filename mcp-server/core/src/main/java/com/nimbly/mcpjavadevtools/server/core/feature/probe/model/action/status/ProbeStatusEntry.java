package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.status;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeCapturePreview;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response.ProbeRuntimeHints;
import java.util.Objects;

/**
 * One input-associated, bounded result from a status operation.
 *
 * @param requestedKey input key used for deterministic association
 * @param key resolved Strict Line Key when valid
 * @param httpStatus status endpoint response status when present
 * @param hitCount observed hit count when endpoint data is trustworthy
 * @param lastHitEpoch observed last Line Hit epoch when endpoint data is trustworthy
 * @param lineResolvable Sidecar line-resolvability signal when present
 * @param lineValidation Sidecar line-validation reason when present
 * @param capturePreview compact safe capture preview when present
 * @param runtime safe runtime hints when present
 */
public record ProbeStatusEntry(
        String requestedKey,
        String key,
        Integer httpStatus,
        Boolean endpointOk,
        Long hitCount,
        Long lastHitEpoch,
        Boolean lineResolvable,
        String lineValidation,
        ProbeCapturePreview capturePreview,
        ProbeRuntimeHints runtime) {

    /**
     * Rejects absent association keys.
     */
    public ProbeStatusEntry {
        Objects.requireNonNull(requestedKey, "requestedKey must not be null");
    }

    /**
     * Returns whether endpoint data confirms at least one Line Hit.
     *
     * @return whether a Line Hit is confirmed
     */
    public boolean lineHit() {
        return !Boolean.FALSE.equals(lineResolvable) && hitCount != null && hitCount > 0;
    }
}
