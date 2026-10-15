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
        usage(output, input);
        write(export.resolve(readmeName(input)), output.toString());
    }

    private static String readmeName(ReadmeInput input) {
        if ("postman".equals(input.mode())) {
            return "README.postman.md";
        }
        String prefix = "performance".equals(input.suiteType()) ? "README.performance." : "README.";
        return prefix + input.mode() + ".md";
    }

    private static String scriptName(ReadmeInput input) {
        if ("postman".equals(input.mode())) {
            return "replay.postman.json";
        }
        String prefix = "performance".equals(input.suiteType()) ? "run-performance-profile." : "run-execution-profile.";
        return prefix + input.mode();
    }

    private static void usage(StringBuilder output, ReadmeInput input) {
        output.append("\n## Usage\n\n")
                .append("1. Open `").append(scriptName(input)).append("`.\n")
                .append("2. Confirm `").append("postman".equals(input.mode()) ? "environment.postman.json" : "project.env")
                .append("` contains the local runtime inputs.\n")
                .append("postman".equals(input.mode())
                        ? "3. Import the collection/environment into Postman or use Newman below.\n\n"
                        : "3. Run the script from this export directory.\n\n")
                .append("This package replays exported workload execution; it does not depend on live MCP orchestration.\n");
        prerequisites(output, input);
        if (!input.options().includeResolvedSecrets()) {
            output.append("\n> Runtime credential values are intentionally redacted. Supply them locally before replay.\n");
        } else {
            output.append("\n> SENSITIVE EXPORT: includeResolvedSecrets=true\n");
        }
    }

    private static void prerequisites(StringBuilder output, ReadmeInput input) {
        output.append("\n## Prerequisites\n\n");
        if ("postman".equals(input.mode())) {
            output.append("Use Postman or Newman with `replay.postman.json` and `environment.postman.json`.\n")
                    .append("Supply receiver credentials in that environment, then run:\n\n")
                    .append("`newman run replay.postman.json -e environment.postman.json`\n\n")
                    .append("Profile context seeds the environment; plan context overrides it, followed by explicit export context values.\n")
                    .append("For multiple plans, edit the plan-specific `__MCPJVM_PLAN_<order>_<key>` environment entries to override those inputs.\n")
                    .append("Provision runtime startup, health readiness and shared scripts before running the collection.\n");
        } else {
            output.append("PowerShell 7 for ps1; Bash and curl for sh.\n")
                    .append("Node.js 22 is required for performance replay and portable dynamic attach.\n")
                    .append("Dynamic attach requires a local JDK 21+ with attach support; the declared JAR, helper and agent are bundled.\n")
                    .append("JMeter plans require Java and Apache JMeter; set `MCP_JAVA_DEV_TOOLS_JMETER_HOME` locally or put JMeter on PATH.\n")
                    .append("Docker startup requires a running Docker engine, Compose, and receiver-provisioned compose files/assets.\n")
                    .append("Review startup commands, health endpoints and scripts; provide other declared local prerequisites.\n");
        }
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
