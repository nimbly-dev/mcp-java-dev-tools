package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.nio.file.Path;

/** Writes the replay filenames required by the execution-export contract. */
final class ExecutionExportReplayFiles {

    private final ExecutionExportReplayFileWriter writer;

    ExecutionExportReplayFiles(ArtifactManagementSupport support) {
        writer = new ExecutionExportReplayFileWriter(support);
    }

    void write(ExecutionExportContext context, String content) {
        Path directory = context.workspace().paths().resolve(
                ".mcpjvm", context.projectName(), "exports", context.exportId());
        writer.write(directory.resolve(ExecutionExportMode.replayFileName(context.mode())), content);
        if (!"postman".equals(context.mode())) {
            writer.write(directory.resolve("run-execution-profile." + context.mode()), content);
            if ("performance".equals(context.workload().plans().getFirst().suiteType())) {
                writer.write(directory.resolve("run-performance-profile." + context.mode()), content);
            }
        }
    }
}
