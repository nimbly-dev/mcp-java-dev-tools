package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads generated JMeter artifact paths for export result assembly. */
final class ExecutionExportJmeterArtifacts {

    List<String> list(Path export) {
        try (var paths = Files.walk(export.resolve("artifacts/jmeter"))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jmx"))
                    .sorted()
                    .map(Path::toString)
                    .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }
}
