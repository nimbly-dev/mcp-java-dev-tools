package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

/** Identifies workload families that require additional export files. */
final class ExecutionExportWorkloadKind {

    private ExecutionExportWorkloadKind() {
    }

    static boolean isPerformance(ExecutionExportWorkload.Workload workload) {
        return workload.plans().stream()
                .anyMatch(plan -> "performance".equals(plan.suiteType()));
    }
}
