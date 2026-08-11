package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;

/**
 * Deterministic result of a capture lookup.
 *
 * @param found whether the requested capture was structurally validated
 * @param capture bounded capture metadata when found
 * @param reason bounded Sidecar-safe reason when unavailable
 */
public record ProbeCaptureResult(
        boolean found,
        ProbeCaptureRecord capture,
        String reason) implements ProbeActionResult {

    /**
     * Enforces mutually exclusive found and unavailable result states.
     */
    public ProbeCaptureResult {
        if (found && capture == null) {
            throw new IllegalArgumentException("found capture results require capture metadata");
        }
        if (!found && capture != null) {
            throw new IllegalArgumentException("unavailable capture results must not include capture metadata");
        }
    }
}
