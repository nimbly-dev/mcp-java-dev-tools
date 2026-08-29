package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.util.Map;

/** Renders a Postman collection for an execution export workload. */
final class ExecutionExportPostmanRenderer {

    private final ArtifactManagementSupport support;

    ExecutionExportPostmanRenderer(ArtifactManagementSupport support) {
        this.support = support;
    }

    String render(ExecutionExportWorkload.Workload workload, ExecutionExportOptions options) {
        ObjectNode collection = support.mapper().createObjectNode();
        ObjectNode info = collection.putObject("info");
        info.put("name", "MCP Java Dev Tools workload replay export ("
                + (options.when() == null ? "default" : options.when()) + ")");
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        var items = collection.putArray("item");
        for (ExecutionExportWorkload.PlanWorkload plan : workload.plans()) {
            for (ExecutionExportWorkload.WorkloadRequest workloadRequest : plan.requests()) {
                ObjectNode item = items.addObject();
                item.put("name", plan.planName() + ":" + workloadRequest.stepId());
                ObjectNode request = item.putObject("request");
                request.put("method", workloadRequest.method());
                var headers = request.putArray("header");
                for (Map.Entry<String, String> header : workloadRequest.headers().entrySet()) {
                    headers.addObject().put("key", header.getKey()).put("value", header.getValue());
                }
                request.putObject("url").put("raw", workloadRequest.url());
                if (workloadRequest.body() != null) {
                    request.putObject("body").put("mode", "raw").put("raw", workloadRequest.body());
                }
            }
        }
        var variables = collection.putArray("variable");
        for (Map.Entry<String, String> binding : options.contextBindings().entrySet()) {
            variables.addObject().put("key", binding.getValue()).put("value", "");
        }
        variables.addObject().put("key", "API_BASE_URL").put("value", "");
        ObjectNode optionsNode = collection.putObject("mcpJvmExportOptions");
        optionsNode.put("includeRuntimeStartup", options.includeRuntimeStartup());
        optionsNode.put("includeHealthcheckGate", options.includeHealthcheckGate());
        optionsNode.put("includeResolvedSecrets", options.includeResolvedSecrets());
        optionsNode.put("contextValuesResolved", options.includeResolvedSecrets());
        return collection.toPrettyString();
    }
}
