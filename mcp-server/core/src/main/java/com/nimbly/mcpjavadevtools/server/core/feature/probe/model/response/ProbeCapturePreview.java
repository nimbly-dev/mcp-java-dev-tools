package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response;

import java.util.List;

/**
 * Bounded safe capture-preview representation shared by Probe read actions.
 *
 * @param available whether a capture preview is available
 * @param captureId capture identifier when available
 * @param capturedAtEpoch capture timestamp when available
 * @param executionStartedAtEpoch execution start timestamp when available
 * @param executionEndedAtEpoch execution end timestamp when available
 * @param executionDurationMs execution duration when available
 * @param threadAllocatedBytesDelta allocated-byte delta when available
 * @param methodKey captured method key when available
 * @param redactionMode Sidecar redaction mode when available
 * @param truncatedAny whether any preview field was truncated
 * @param executionPaths optional bounded execution paths
 */
public record ProbeCapturePreview(
        Boolean available,
        String captureId,
        Long capturedAtEpoch,
        Long executionStartedAtEpoch,
        Long executionEndedAtEpoch,
        Long executionDurationMs,
        Long threadAllocatedBytesDelta,
        String methodKey,
        String redactionMode,
        Boolean truncatedAny,
        List<String> executionPaths) {

    /**
     * Defensively copies execution paths after action-owned compaction.
     */
    public ProbeCapturePreview {
        executionPaths = executionPaths == null ? List.of() : List.copyOf(executionPaths);
    }
}
