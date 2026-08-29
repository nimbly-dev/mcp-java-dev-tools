package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.util.List;

/** Writes the environment, replay scripts, optional performance bundle, and README. */
final class ExecutionExportReplayWriter {

    private final ArtifactManagementSupport support;
    private final ExecutionExportEnvironmentWriter environmentWriter;
    private final ExecutionExportScriptPreparation scriptPreparation;
    private final ExecutionExportReplayRenderer renderer;
    private final ExecutionExportReplayFiles replayFiles;
    private final ExecutionExportReplayReadme readme;

    ExecutionExportReplayWriter(ArtifactManagementSupport support) {
        this.support = support;
        environmentWriter = new ExecutionExportEnvironmentWriter(support);
        scriptPreparation = new ExecutionExportScriptPreparation(support);
        renderer = new ExecutionExportReplayRenderer(support);
        replayFiles = new ExecutionExportReplayFiles(support);
        readme = new ExecutionExportReplayReadme();
    }

    void write(ExecutionExportContext context) {
        environmentWriter.write(context);
        List<ExecutionExportScriptInvocation> scripts = scriptPreparation.prepare(context);
        boolean performance = ExecutionExportWorkloadKind.isPerformance(context.workload());
        List<String> jmeterPaths = performance
                ? new ExecutionExportPerformanceArtifacts(support.mapper()).write(
                        context.export(), context.exportId(),
                        context.profile() == null
                                ? "stop_on_fail" : context.profile().path("executionPolicy").asText("stop_on_fail"),
                        context.workload())
                : List.of();
        ExecutionExportPortableSidecar.writeIfRequired(
                context.export(), context.workspace().root(), context.options().workspace(),
                context.profile(), support.mapper());
        String content = renderer.render(
                context.mode(), context.workload(), context.options(), context.profile(), scripts);
        if (performance) {
            content += ExecutionExportMode.performanceRunner(context.mode());
        }
        replayFiles.write(context, content);
        readme.write(context, jmeterPaths);
    }
}
