package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerDownload;
import java.io.IOException;

/**
 * Writes a bounded Sidecar profiler download to the user-requested local path.
 */
public interface ProbeProfilerOutputStore {

    /**
     * Writes one already-bounded binary profiler response.
     *
     * @param outputPath requested output path
     * @param content bounded profiler bytes
     * @return normalized local output result
     * @throws IOException when the requested path cannot be written
     */
    ProbeProfilerDownload write(String outputPath, byte[] content) throws IOException;
}
