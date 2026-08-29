package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import java.util.Comparator;
import java.util.List;

/** Builds the README input for a generated execution-export package. */
final class ExecutionExportReplayReadme {

    void write(ExecutionExportContext context, List<String> jmeterPaths) {
        if ("postman".equals(context.mode())) {
            return;
        }
        List<String> plans = context.workload().plans().stream()
                .sorted(Comparator.comparingInt(ExecutionExportWorkload.PlanWorkload::order))
                .map(plan -> "[" + plan.order() + "] " + plan.planName() + " (source_status=blocked)")
                .toList();
        ExecutionExportReadme.write(
                context.export(), new ExecutionExportReadme.ReadmeInput(
                        context.mode(),
                        context.exportId(),
                        context.workload().plans().getFirst().suiteType(),
                        context.workload().executionProfile() == null
                                ? "ad-hoc" : context.workload().executionProfile(),
                        context.options(), plans, jmeterPaths));
    }
}
