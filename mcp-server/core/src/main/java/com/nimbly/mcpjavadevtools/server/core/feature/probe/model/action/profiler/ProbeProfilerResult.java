package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;

/**
 * Bounded profiler lifecycle result without unbounded profiler bytes.
 *
 * @param command effective lifecycle command
 * @param status Sidecar status
 * @param provider effective provider
 * @param supported whether the Sidecar supports the provider
 * @param sessionId effective session identity
 * @param startedAtEpochMs start timestamp
 * @param stoppedAtEpochMs stop timestamp
 * @param event provider-specific event
 * @param intervalNanos effective sampling interval
 * @param outputPath bounded Sidecar output path metadata
 * @param outputFormat output format
 * @param detail bounded Sidecar-safe detail
 * @param downloadedBytes bounded download byte count when requested
 */
public record ProbeProfilerResult(
        String command,
        String status,
        Boolean supported,
        String provider,
        String sessionId,
        Long startedAtEpochMs,
        Long stoppedAtEpochMs,
        String event,
        Long intervalNanos,
        String outputPath,
        String outputFormat,
        String detail,
        Long downloadedBytes) implements ProbeActionResult {
}
