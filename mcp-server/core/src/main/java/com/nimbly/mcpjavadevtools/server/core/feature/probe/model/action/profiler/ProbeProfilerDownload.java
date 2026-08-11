package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler;

/**
 * Bounded local result of a profiler-output download.
 *
 * @param outputPath normalized local output path
 * @param bytesWritten bounded byte count written to that path
 */
public record ProbeProfilerDownload(String outputPath, long bytesWritten) {
}
