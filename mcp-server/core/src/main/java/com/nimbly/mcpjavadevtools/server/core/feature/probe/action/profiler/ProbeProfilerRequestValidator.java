package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerCommand;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerRequest;

/**
 * Validates command-specific profiler input before endpoint interaction.
 */
public final class ProbeProfilerRequestValidator {

    private ProbeProfilerRequestValidator() {
    }

    public static boolean isValid(ProbeProfilerRequest request) {
        if (request.command() == null || (request.outputFormat() != null && !"jfr".equals(request.outputFormat()))) {
            return false;
        }
        if (request.intervalNanos() != null && request.intervalNanos() <= 0) {
            return false;
        }
        if (request.command() == ProbeProfilerCommand.START && request.sessionId() == null) {
            return false;
        }
        return request.command() != ProbeProfilerCommand.DOWNLOAD || request.outputPath() != null;
    }
}
