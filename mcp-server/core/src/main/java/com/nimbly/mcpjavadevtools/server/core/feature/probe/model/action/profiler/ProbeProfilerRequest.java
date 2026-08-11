package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed Sidecar profiler lifecycle request.
 *
 * @param targetSelector direct or registered Probe target selector
 * @param command requested lifecycle command
 * @param sessionId optional session identity, required to start
 * @param provider optional provider intent
 * @param event optional provider-specific event
 * @param intervalNanos optional positive sampling interval
 * @param outputPath optional Sidecar output path, required to download
 * @param outputFormat optional Sidecar output format
 * @param timeout optional bounded endpoint timeout
 */
public record ProbeProfilerRequest(
        ProbeTargetSelector targetSelector,
        ProbeProfilerCommand command,
        String sessionId,
        ProbeProfilerProvider provider,
        String event,
        Long intervalNanos,
        String outputPath,
        String outputFormat,
        Duration timeout) implements ProbeRequest {

    /**
     * Normalizes optional boundary strings before lifecycle validation.
     */
    public ProbeProfilerRequest {
        sessionId = normalize(sessionId);
        event = normalize(event);
        outputPath = normalize(outputPath);
        outputFormat = normalize(outputFormat);
    }

    @Override
    public ProbeAction action() {
        return ProbeAction.PROFILER;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
