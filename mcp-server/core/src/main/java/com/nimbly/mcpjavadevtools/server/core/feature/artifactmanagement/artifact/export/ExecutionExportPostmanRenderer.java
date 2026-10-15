package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Renders a Postman collection for an execution export workload. */
final class ExecutionExportPostmanRenderer {

    private static final String EXTRACTED_PREFIX = "__MCPJVM_EXTRACTED_";

    private final ArtifactManagementSupport support;

    ExecutionExportPostmanRenderer(ArtifactManagementSupport support) {
        this.support = support;
    }

    String render(ExecutionExportContext context, List<ExecutionExportScriptInvocation> scripts) {
        var workload = context.workload();
        var options = context.options();
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
                extraction(item, plan.contract(), workloadRequest.stepId(), options);
                planContext(item, context, plan);
                request(item.putObject("request"), workloadRequest);
            }
        }
        prerequest(collection, context, scripts);
        var variables = collection.putArray("variable");
        Set<String> names = new LinkedHashSet<>(options.contextBindings().values());
        names.add("API_BASE_URL");
        for (String name : names) {
            variables.addObject().put("key", name).put("value", "");
        }
        ObjectNode optionsNode = collection.putObject("mcpJvmExportOptions");
        optionsNode.put("includeRuntimeStartup", options.includeRuntimeStartup());
        optionsNode.put("includeHealthcheckGate", options.includeHealthcheckGate());
        optionsNode.put("includeResolvedSecrets", options.includeResolvedSecrets());
        optionsNode.put("contextValuesResolved", options.includeResolvedSecrets());
        return collection.toPrettyString();
    }

    private void request(ObjectNode request, ExecutionExportWorkload.WorkloadRequest workload) {
        request.put("method", workload.method()).put("url", workload.url());
        var headers = request.putArray("header");
        workload.headers().forEach((key, value) -> headers.addObject().put("key", key).put("value", value));
        if (workload.body() != null) {
            request.putObject("body").put("mode", "raw").put("raw", workload.body());
        }
    }

    private void planContext(ObjectNode item, ExecutionExportContext context, ExecutionExportWorkload.PlanWorkload plan) {
        Set<String> scopedKeys = new LinkedHashSet<>();
        for (var selected : context.workload().plans()) {
            scopedKeys.addAll(ExecutionExportEnvironmentWriter.planBindings(context, selected).keySet());
        }
        if (scopedKeys.isEmpty()) {
            return;
        }
        var script = item.withArray("event").addObject().put("listen", "prerequest").putObject("script");
        script.put("type", "text/javascript");
        var lines = script.putArray("exec");
        scopedKeys.forEach(key -> lines.add("pm.variables.unset(" + quote(key) + ");"));
        ExecutionExportEnvironmentWriter.planBindings(context, plan).forEach((key, scoped) ->
                lines.add("pm.variables.set(" + quote(key) + ",pm.environment.get(" + quote(scoped) + "));"));
        scopedKeys.forEach(key -> {
            String promoted = quote(EXTRACTED_PREFIX + key);
            lines.add("if(pm.variables.has(" + promoted + "))pm.variables.set(" + quote(key)
                    + ",pm.variables.get(" + promoted + "));");
        });
    }

    private void extraction(ObjectNode item, JsonNode contract, String id, ExecutionExportOptions options) {
        for (JsonNode step : contract.path("steps")) {
            if (!id.equals(step.path("id").asText()) || step.path("extract").isEmpty()) {
                continue;
            }
            var script = item.putArray("event").addObject().put("listen", "test").putObject("script");
            script.put("type", "text/javascript");
            var lines = script.putArray("exec");
            lines.add("let body; try { body = pm.response.json(); } catch {}")
                    .add("const envelope={response:{body,statusCode:pm.response.code,headers:pm.response.headers.toObject()}};");
            for (JsonNode mapping : step.path("extract")) {
                String key = mapping.path("as").asText();
                String envKey = options.contextBindings().getOrDefault(key, ExecutionExportEnvironmentFormat.environmentKey(key));
                lines.add("{const value=" + quote(mapping.path("from").asText())
                        + ".split('.').reduce((v,k)=>v?.[k],envelope);"
                        + "if(value!=null){pm.environment.set(" + quote(envKey) + ",String(value));"
                        + "pm.variables.set(" + quote(EXTRACTED_PREFIX + envKey) + ",String(value));}}");
            }
        }
    }

    private void prerequest(ObjectNode collection, ExecutionExportContext context, List<ExecutionExportScriptInvocation> scripts) {
        if (scripts.isEmpty()) {
            return;
        }
        var output = collection.putArray("event").addObject().put("listen", "prerequest").putObject("script");
        output.put("type", "text/javascript");
        var lines = output.putArray("exec");
        for (var script : scripts) {
            if ((script.command() + " " + String.join(" ", script.args())).matches(
                    "(?is).*\\b(docker|compose|podman|kubectl|helm|terraform|ansible|vagrant|minikube)\\b.*")) {
                throw new ArtifactOperationException("postman_provisioning_not_supported", "Provision runtime prerequisites before Postman replay");
            }
            String file = script.args().stream().filter(value -> value.startsWith("__MCPJVM_SCRIPT__")
                    && value.matches("(?i).*\\.(js|mjs|cjs)$")).findFirst().orElseThrow(() ->
                    new ArtifactOperationException("postman_script_conversion_required", "Postman requires a Postman JavaScript prerequisite"));
            var path = context.workspace().paths().check(context.export().resolve(file.substring("__MCPJVM_SCRIPT__".length())));
            String text = support.jsonStore().readText(path);
            if (!java.util.regex.Pattern.compile("\\b(pm|postman)\\s*\\.").matcher(text).find()) {
                throw new ArtifactOperationException("postman_script_invalid_format", "Selected script does not use the Postman API");
            }
            lines.add(text);
        }
    }

    private String quote(String value) {
        return support.mapper().valueToTree(value).toString();
    }
}
