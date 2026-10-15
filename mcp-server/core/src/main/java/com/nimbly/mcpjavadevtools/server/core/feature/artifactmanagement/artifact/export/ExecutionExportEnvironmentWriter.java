package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.policy.ArtifactRedactionPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

/** Writes the redacted or resolved project environment shipped with an export. */
final class ExecutionExportEnvironmentWriter {

    private final ArtifactManagementSupport support;

    ExecutionExportEnvironmentWriter(ArtifactManagementSupport support) {
        this.support = support;
    }

    void write(ExecutionExportContext context) {
        ExecutionExportOptions options = context.options();
        Map<String, String> values = resolvedValues(context);
        List<String> lines = new ArrayList<>();
        lines.add("# Runtime inputs for run-execution-profile export");
        lines.add(options.includeResolvedSecrets()
                ? "# SENSITIVE EXPORT: includeResolvedSecrets=true."
                : "# Secret-like values are blanked because includeResolvedSecrets=false.");
        if (options.when() != null) {
            lines.add("# when=" + ExecutionExportEnvironmentFormat.dotenvValue(options.when()));
        }
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            lines.add(entry.getKey() + "=" + ExecutionExportEnvironmentFormat.dotenvValue(entry.getValue()));
        });
        Path target = context.workspace().paths().resolve(
                ".mcpjvm", context.projectName(), "exports", context.exportId(), "project.env");
        support.jsonStore().writeText(target, String.join("\n", lines) + "\n");
        if ("postman".equals(context.mode())) {
            writePostman(context, values);
        }
    }

    private Map<String, String> resolvedValues(ExecutionExportContext context) {
        var options = context.options();
        Map<String, String> values = runtimeValues(context);
        if ("postman".equals(context.mode()) && context.profile() != null) {
            putContext(values, context.profile().path("providedContext"), options);
            if (context.workload().plans().size() == 1) {
                putContext(values, selectedPlan(context, context.workload().plans().getFirst().planName()).path("providedContext"), options);
            } else {
                for (var plan : context.workload().plans()) {
                    Map<String, String> provided = new LinkedHashMap<>();
                    putContext(provided, selectedPlan(context, plan.planName()).path("providedContext"), options);
                    putOverrides(provided, options);
                    planBindings(context, plan).forEach((key, scoped) -> values.put(scoped, provided.get(key)));
                }
            }
        }
        putOverrides(values, options);
        if (!options.includeResolvedSecrets()) {
            Set<String> sensitive = sensitiveKeys(context);
            values.replaceAll((key, value) -> sensitive.contains(key) || secretKey(key) ? "" : value);
        }
        return values;
    }

    private Map<String, String> runtimeValues(ExecutionExportContext context) {
        Map<String, String> values = new LinkedHashMap<>();
        String envFile = context.options().workspace().path("envFile").asText("").trim();
        if (!envFile.isBlank()) {
            Path source = context.workspace().paths().check(context.workspace().root().resolve(envFile));
            ExecutionExportEnvironmentFormat.parseDotEnv(support.jsonStore().readText(source), values);
        }
        for (String key : context.options().contextBindings().values()) {
            values.putIfAbsent(key, System.getenv().getOrDefault(key, ""));
        }
        return values;
    }

    private static void putContext(Map<String, String> values, JsonNode provided, ExecutionExportOptions options) {
        provided.fields().forEachRemaining(entry -> {
            if (entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                values.put(environmentKey(options, entry.getKey()), entry.getValue().asText());
            }
        });
    }

    private static void putOverrides(Map<String, String> values, ExecutionExportOptions options) {
        options.contextValues().forEach((key, value) -> values.put(environmentKey(options, key), value));
    }

    private static String environmentKey(ExecutionExportOptions options, String key) {
        return options.contextBindings().getOrDefault(key, ExecutionExportEnvironmentFormat.environmentKey(key));
    }

    private static JsonNode selectedPlan(ExecutionExportContext context, String name) {
        if (context.profile() != null) {
            for (JsonNode plan : context.profile().path("plans")) {
                if (name.equals(plan.path("planName").asText())) {
                    return plan;
                }
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    static Map<String, String> planBindings(ExecutionExportContext context, ExecutionExportWorkload.PlanWorkload plan) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (context.workload().plans().size() > 1) {
            selectedPlan(context, plan.planName()).path("providedContext").fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                    String key = environmentKey(context.options(), entry.getKey());
                    bindings.put(key, "__MCPJVM_PLAN_" + plan.order() + "_" + key);
                }
            });
        }
        return bindings;
    }

    private static Set<String> sensitiveKeys(ExecutionExportContext context) {
        Set<String> keys = new LinkedHashSet<>();
        context.options().contextBindings().forEach((logical, environment) -> {
            if (secretKey(logical)) {
                keys.add(environment);
            }
        });
        for (var plan : context.workload().plans()) {
            planBindings(context, plan).forEach((key, scoped) -> {
                if (keys.contains(key) || secretKey(key)) {
                    keys.add(scoped);
                }
            });
        }
        return keys;
    }

    private static boolean secretKey(String key) {
        return ExecutionExportEnvironmentFormat.isSensitiveEnvKey(key)
                || "[REDACTED]".equals(ArtifactRedactionPolicy.sanitizeValue(key, "export-value"));
    }

    private void writePostman(ExecutionExportContext context, Map<String, String> values) {
        var environment = support.mapper().createObjectNode();
        environment.put("name", context.exportId()).put("_postman_variable_scope", "environment");
        var entries = environment.putArray("values");
        Set<String> sensitiveKeys = sensitiveKeys(context);
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            boolean sensitive = sensitiveKeys.contains(entry.getKey()) || secretKey(entry.getKey());
            entries.addObject().put("key", entry.getKey()).put("enabled", true)
                    .put("type", sensitive ? "secret" : "default")
                    .put("value", sensitive && !context.options().includeResolvedSecrets() ? "" : entry.getValue());
        });
        support.jsonStore().writeText(context.export().resolve("environment.postman.json"), environment.toPrettyString() + "\n");
    }
}
