package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;

/** Renders the operator documentation shipped with an execution export. */
public final class ExecutionExportReadme {

    private ExecutionExportReadme() {
    }

    public static void write(Path export, ReadmeInput input) {
        String script = "performance".equals(input.suiteType())
                ? "run-performance-profile." + input.mode() : "run-execution-profile." + input.mode();
        String name = "performance".equals(input.suiteType())
                ? "README.performance." + input.mode() + ".md" : "README." + input.mode() + ".md";
        StringBuilder output = new StringBuilder("# Execution Profile ")
                .append(input.mode().toUpperCase(Locale.ROOT)).append(" Export\n\n")
                .append("- exportId: `").append(input.exportId()).append("`\n")
                .append("- SuiteType: `").append(input.suiteType()).append("`\n")
                .append("- ExecutionProfile: `").append(input.executionProfile()).append("`\n")
                .append("- IncludeResolvedSecrets: `").append(input.options().includeResolvedSecrets()).append("`\n")
                .append("- IncludeRuntimeStartup: `").append(input.options().includeRuntimeStartup()).append("`\n")
                .append("- IncludeHealthcheckGate: `").append(input.options().includeHealthcheckGate()).append("`\n\n")
                .append("## Plan Order\n\n");
        for (String line : input.planLines()) {
            output.append("1. ").append(line).append('\n');
        }
        if (!input.jmeterPaths().isEmpty()) {
            output.append("\n## JMeter Artifacts\n\n");
            for (String path : input.jmeterPaths()) {
                output.append("- `").append(path).append("`\n");
            }
        }
        output.append("\n## Usage\n\n")
                .append("1. Open `").append(script).append("`.\n")
                .append("2. Confirm `project.env` contains the local runtime inputs.\n")
                .append("3. Run the script from inside the project workspace.\n\n")
                .append("This package replays exported workload execution; it does not depend on live MCP orchestration.\n");
        if (!input.options().includeResolvedSecrets()) {
            output.append("\n> `project.env` is intentionally redacted. Supply credentials locally before replay.\n");
        } else {
            output.append("\n> SENSITIVE EXPORT: includeResolvedSecrets=true\n");
        }
        write(export.resolve(name), output.toString());
    }

    public record ReadmeInput(
            String mode,
            String exportId,
            String suiteType,
            String executionProfile,
            ExecutionExportOptions options,
            List<String> planLines,
            List<String> jmeterPaths) {
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ArtifactOperationException("execution_export_write_failed",
                    "Execution export could not be persisted");
        }
    }
}
