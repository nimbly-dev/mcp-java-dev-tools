package com.nimbly.mcpjavadevtools.server.core.feature.probe.action.profiler;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler.ProbeProfilerDownload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local filesystem writer used only for an explicit profiler download request.
 */
public class LocalProbeProfilerOutputStore implements ProbeProfilerOutputStore {

    /**
     * Creates parent directories and writes the bounded response atomically per Files.write semantics.
     *
     * @param outputPath requested user output path
     * @param content bounded Sidecar profiler bytes
     * @return normalized path and count written
     * @throws IOException when local output cannot be written
     */
    @Override
    public ProbeProfilerDownload write(String outputPath, byte[] content) throws IOException {
        Path path = Path.of(outputPath).toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, content);
        return new ProbeProfilerDownload(path.toString(), content.length);
    }
}
