package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.capture;

import java.util.List;

/**
 * Bounded, value-free capture metadata returned by the Sidecar.
 *
 * @param captureId capture identity
 * @param methodKey captured method identity
 * @param capturedAtEpoch capture timestamp
 * @param executionStartedAtEpoch execution start timestamp
 * @param executionEndedAtEpoch execution end timestamp
 * @param executionDurationMs execution duration
 * @param threadAllocatedBytesDelta allocated-byte delta
 * @param redactionMode Sidecar redaction mode
 * @param argsCount captured argument count without values
 * @param hasReturnValue whether a return value exists
 * @param hasThrownValue whether a thrown value exists
 * @param truncatedAny whether the Sidecar truncated any value
 * @param executionPaths optionally retained bounded execution-path summaries
 */
public record ProbeCaptureRecord(
        String captureId,
        String methodKey,
        Long capturedAtEpoch,
        Long executionStartedAtEpoch,
        Long executionEndedAtEpoch,
        Long executionDurationMs,
        Long threadAllocatedBytesDelta,
        String redactionMode,
        Integer argsCount,
        Boolean hasReturnValue,
        Boolean hasThrownValue,
        Boolean truncatedAny,
        List<String> executionPaths) {

    /**
     * Defensively copies action-compacted execution paths.
     */
    public ProbeCaptureRecord {
        executionPaths = executionPaths == null ? List.of() : List.copyOf(executionPaths);
    }
}
