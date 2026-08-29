package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;

/** Builds the deterministic manifest persisted with an execution export. */
final class ExecutionExportManifestWriter {

    private final ArtifactManagementSupport support;

    ExecutionExportManifestWriter(ArtifactManagementSupport support) {
        this.support = support;
    }

    ObjectNode build(
            String exportId,
            String projectName,
            String mode,
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options) {
        ObjectNode manifest = support.mapper().createObjectNode();
        manifest.put("exportId", exportId);
        manifest.put("projectName", projectName);
        manifest.put("mode", mode);
        manifest.put("executionProfile", workload.executionProfile() == null
                ? "ad-hoc" : workload.executionProfile());
        manifest.put("includeResolvedSecrets", options.includeResolvedSecrets());
        manifest.put("includeRuntimeStartup", options.includeRuntimeStartup());
        manifest.put("includeHealthcheckGate", options.includeHealthcheckGate());
        if (options.when() != null) {
            manifest.put("when", options.when());
        }
        ObjectNode bindings = manifest.putObject("contextBindings");
        options.contextBindings().forEach(bindings::put);
        ObjectNode values = manifest.putObject("contextValues");
        options.contextValues().keySet().forEach(key -> values.put(key, "[REDACTED]"));
        addWorkloadSummary(manifest, workload);
        return manifest;
    }

    private void addWorkloadSummary(
            ObjectNode manifest, ExecutionExportWorkload.Workload workload) {
        manifest.put("replayTarget", "selected_plan_workload");
        var plans = manifest.putArray("workload");
        for (ExecutionExportWorkload.PlanWorkload plan : workload.plans()) {
            ObjectNode planNode = plans.addObject();
            planNode.put("order", plan.order());
            planNode.put("suiteType", plan.suiteType());
            planNode.put("planName", plan.planName());
            var steps = planNode.putArray("steps");
            for (ExecutionExportWorkload.WorkloadRequest request : plan.requests()) {
                ObjectNode step = steps.addObject();
                step.put("stepId", request.stepId());
                step.put("method", request.method());
                step.put("url", request.url());
                step.put("hasBody", request.body() != null);
                var headerNames = step.putArray("headerNames");
                request.headers().keySet().forEach(headerNames::add);
            }
        }
    }
}
