package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the redacted or resolved project environment shipped with an export. */
final class ExecutionExportEnvironmentWriter {

    private final ArtifactManagementSupport support;

    ExecutionExportEnvironmentWriter(ArtifactManagementSupport support) {
        this.support = support;
    }

    void write(ExecutionExportContext context) {
        ExecutionExportOptions options = context.options();
        Map<String, String> values = new LinkedHashMap<>();
        String envFile = options.workspace().path("envFile").asText("").trim();
        if (!envFile.isBlank()) {
            Path source = context.workspace().paths().check(context.workspace().root().resolve(envFile));
            ExecutionExportEnvironmentFormat.parseDotEnv(support.jsonStore().readText(source), values);
        }
        for (String envKey : options.contextBindings().values()) {
            values.putIfAbsent(envKey, System.getenv().getOrDefault(envKey, ""));
        }
        for (Map.Entry<String, String> value : options.contextValues().entrySet()) {
            String envKey = options.contextBindings().getOrDefault(
                    value.getKey(), ExecutionExportEnvironmentFormat.environmentKey(value.getKey()));
            values.put(envKey, value.getValue());
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Runtime inputs for run-execution-profile export");
        lines.add(options.includeResolvedSecrets()
                ? "# SENSITIVE EXPORT: includeResolvedSecrets=true."
                : "# Secret-like values are blanked because includeResolvedSecrets=false.");
        if (options.when() != null) {
            lines.add("# when=" + ExecutionExportEnvironmentFormat.dotenvValue(options.when()));
        }
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String value = !options.includeResolvedSecrets()
                    && ExecutionExportEnvironmentFormat.isSensitiveEnvKey(entry.getKey())
                    ? "" : entry.getValue();
            lines.add(entry.getKey() + "=" + ExecutionExportEnvironmentFormat.dotenvValue(value));
        });
        Path target = context.workspace().paths().resolve(
                ".mcpjvm", context.projectName(), "exports", context.exportId(), "project.env");
        support.jsonStore().writeText(target, String.join("\n", lines) + "\n");
    }
}
