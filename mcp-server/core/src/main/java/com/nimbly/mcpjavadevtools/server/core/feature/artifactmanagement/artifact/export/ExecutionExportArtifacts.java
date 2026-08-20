package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactPathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;

/** Purpose-owned execution export Artifact boundary used by export actions. */
public final class ExecutionExportArtifacts {
    private final ArtifactManagementSupport support;

    /** Creates the export owner. */
    public ExecutionExportArtifacts(ArtifactManagementSupport support) {
        this.support = support;
    }

    /** Reads one export package. */
    public ArtifactManagementResult read(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            String exportId = request.child("query")
                    .flatMap(node -> optionalText(node, "exportId"))
                    .orElseThrow(() -> new ArtifactOperationException("export_id_required", "exportId is required"));
            ArtifactPathPolicy.validateSegment(exportId);
            Path export = workspace.paths().resolve(".mcpjvm", projectName, "exports", exportId);
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "exportId", exportId,
                    "files", support.jsonStore().files(export)));
        });
    }

    /** Lists export packages. */
    public ArtifactManagementResult list(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> {
            String projectName = support.resolveProject(workspace, request);
            Path exports = workspace.paths().resolve(".mcpjvm", projectName, "exports");
            return support.success(request, Map.of(
                    "projectName", projectName,
                    "exportFolders", support.jsonStore().directories(exports)));
        });
    }

    /** Generates one bounded replay package. */
    public ArtifactManagementResult generate(ArtifactManagementRequest request) {
        return support.withWorkspace(request, workspace -> generateExport(request, workspace));
    }

    private ArtifactManagementResult generateExport(
            ArtifactManagementRequest request, ArtifactManagementSupport.Workspace workspace) {
        String mode = resolveMode(request);
        String projectName = support.resolveProject(workspace, request);
        JsonNode projectArtifact = readProjectArtifact(workspace, projectName);
        ExecutionExportOptions options = ExecutionExportOptions.resolve(projectArtifact, request);
        ExecutionExportWorkload.Workload workload = resolveWorkload(
                workspace, projectName, projectArtifact, request, options);
        return persistExport(request, workspace, projectName, mode, workload, options);
    }

    private String resolveMode(ArtifactManagementRequest request) {
        String mode = request.text("mode").or(() -> request.text("type")).orElseThrow(
                () -> new ArtifactOperationException("execution_export_mode_required", "mode is required"));
        if (request.text("mode").isPresent() && request.text("type").isPresent()
                && !request.text("mode").get().equals(request.text("type").get())) {
            throw new ArtifactOperationException("execution_export_mode_conflict", "mode and type alias must match");
        }
        if (!List.of("ps1", "sh", "postman").contains(mode)) {
            throw new ArtifactOperationException("execution_export_mode_invalid", "export mode is unsupported");
        }
        return mode;
    }

    private ExecutionExportWorkload.Workload resolveWorkload(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            JsonNode projectArtifact,
            ArtifactManagementRequest request,
            ExecutionExportOptions options) {
        ExecutionExportWorkload.Workload workload = ExecutionExportWorkload.resolve(
                support.jsonStore(), workspace.paths(), projectName, projectArtifact,
                new ExecutionExportWorkload.WorkloadSelection(
                        request.text("executionProfile").orElse(null), request.text("planName").orElse(null),
                        options.contextBindings()));
        if (workload.plans().stream().anyMatch(plan -> "security".equals(plan.suiteType()))) {
            throw new ArtifactOperationException("security_export_unsupported",
                    "Security Suite execution profiles do not produce workload replay exports");
        }
        if (workload.plans().stream().anyMatch(plan -> "performance".equals(plan.suiteType()))
                && "postman".equals(request.text("mode").orElse(request.text("type").orElse("")))) {
            throw new ArtifactOperationException("performance_export_mode_unsupported",
                    "performance exports support ps1 and sh modes only");
        }
        return workload;
    }

    private ArtifactManagementResult persistExport(
            ArtifactManagementRequest request,
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String mode,
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options) {
        String exportId = "export-" + stableExportId(projectName, request.input().toString());
        Path export = workspace.paths().resolve(".mcpjvm", projectName, "exports", exportId);
        JsonNode profile = selectedProfile(options.workspace(), workload.executionProfile());
        ExportContext context = new ExportContext(
                workspace, projectName, exportId, export, mode, workload, options, profile);
        support.jsonStore().write(
                workspace.paths().resolve(".mcpjvm", projectName, "exports", exportId, "manifest.json"),
                buildManifest(exportId, projectName, mode, workload, options));
        writeExportFiles(context);
        return exportResult(request, context);
    }

    private ObjectNode buildManifest(
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
        addStringMap(manifest, "contextBindings", options.contextBindings());
        addStringMapKeys(manifest, "contextValues", options.contextValues());
        addWorkloadSummary(manifest, workload);
        return manifest;
    }

    private void writeExportFiles(ExportContext context) {
        writeProjectEnv(context.workspace(), context.projectName(), context.exportId(), context.options());
        List<ScriptInvocation> scripts = prepareScripts(
                context.workspace(), context.projectName(), context.exportId(), context.profile(), context.options());
        String content = renderReplay(
                context.mode(), context.workload(), context.options(), context.profile(), scripts);
        writeReplayFile(context.workspace().paths().resolve(
                ".mcpjvm", context.projectName(), "exports", context.exportId(),
                replayFileName(context.mode())), content);
        if (!"postman".equals(context.mode())) {
            writeReplayFile(context.workspace().paths().resolve(
                    ".mcpjvm", context.projectName(), "exports", context.exportId(),
                    "run-execution-profile." + context.mode()), content);
            if ("performance".equals(context.workload().plans().getFirst().suiteType())) {
                writeReplayFile(context.workspace().paths().resolve(
                        ".mcpjvm", context.projectName(), "exports", context.exportId(),
                        "run-performance-profile." + context.mode()), content);
            }
        }
    }

    private ArtifactManagementResult exportResult(
            ArtifactManagementRequest request, ExportContext context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("artifactType", request.artifactType().value());
        details.put("action", request.action().value());
        details.put("projectName", context.projectName());
        details.put("exportId", context.exportId());
        details.put("mode", context.mode());
        details.put("suiteType", context.workload().plans().getFirst().suiteType());
        details.put("executionProfile", context.workload().executionProfile() == null
                ? "ad-hoc" : context.workload().executionProfile());
        details.put("exportDirAbs", context.export().toString());
        details.put("path", context.workspace().paths().relative(context.export()));
        details.put("files", support.jsonStore().files(context.export()));
        details.put("output", exportOutput(context.export(), context.mode()));
        return new ArtifactManagementResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(), details);
    }

    private Map<String, Object> exportOutput(Path export, String mode) {
        String replayName = replayFileName(mode);
        if ("postman".equals(mode)) {
            return Map.of("collectionPathAbs", export.resolve(replayName).toString(),
                    "environmentPathAbs", export.resolve("project.env").toString());
        }
        return Map.of("scriptPathAbs", export.resolve("run-execution-profile." + mode).toString());
    }

    private JsonNode readProjectArtifact(ArtifactManagementSupport.Workspace workspace, String projectName) {
        Path path = workspace.paths().resolve(".mcpjvm", projectName, "projects.json");
        return support.jsonStore().read(path);
    }

    private void addWorkloadSummary(ObjectNode manifest, ExecutionExportWorkload.Workload workload) {
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

    private String renderReplay(
            String mode,
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options,
            JsonNode profile,
            List<ScriptInvocation> scripts) {
        return switch (mode) {
            case "ps1" -> renderPowerShell(workload, options, profile, scripts);
            case "sh" -> renderShell(workload, options, profile, scripts);
            case "postman" -> postmanCollection(workload, options);
            default -> throw new ArtifactOperationException(
                    "execution_export_mode_invalid", "export mode is unsupported");
        };
    }

    private String renderPowerShell(
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options,
            JsonNode profile,
            List<ScriptInvocation> scripts) {
        StringBuilder script = powerShellHeader(options);
        appendPowerShellRuntime(script, options, profile);
        appendPowerShellScripts(script, scripts);
        appendPowerShellHealthchecks(script, options.workspace(), options.includeHealthcheckGate());
        appendPowerShellWorkload(script, workload);
        return script.toString();
    }

    private StringBuilder powerShellHeader(ExecutionExportOptions options) {
        String templatePattern = "'\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}'";
        StringBuilder script = new StringBuilder("$ErrorActionPreference = 'Stop'\n")
                .append("# MCPJVM export when=")
                .append(psSingleQuoted(options.when() == null ? "default" : options.when())).append("\n")
                .append("# includeRuntimeStartup=").append(options.includeRuntimeStartup())
                .append(" includeHealthcheckGate=").append(options.includeHealthcheckGate())
                .append(" includeResolvedSecrets=").append(options.includeResolvedSecrets()).append("\n")
                .append("$__mcpjvm_env_file = Join-Path $PSScriptRoot 'project.env'\n")
                .append("if (Test-Path -LiteralPath $__mcpjvm_env_file) {\n")
                .append("  Get-Content -LiteralPath $__mcpjvm_env_file | ForEach-Object { ")
                .append("if ($_ -match '^(\\w+)=(.*)$') { ")
                .append("[Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('\\\"')) } }\n")
                .append("}\n")
                .append("function Resolve-McpJvmTemplate {\n")
                .append("  param([string]$Value)\n")
                .append("  return [regex]::Replace($Value, ")
                .append(templatePattern)
                .append(", { param($Match) $Value = [Environment]::GetEnvironmentVariable(")
                .append("$Match.Groups[1].Value); if ($null -eq $Value) { '' } else { $Value } })\n")
                .append("}\n\n");
        return script;
    }

    private void appendPowerShellWorkload(
            StringBuilder script, ExecutionExportWorkload.Workload workload) {
        for (ExecutionExportWorkload.PlanWorkload plan : workload.plans()) {
            script.append("Write-Host ").append(psSingleQuoted(
                    "[" + plan.suiteType() + ":" + plan.planName() + "] replay workload")).append("\n");
            for (ExecutionExportWorkload.WorkloadRequest request : plan.requests()) {
                script.append("$__step_url = Resolve-McpJvmTemplate ")
                        .append(psSingleQuoted(request.url())).append("\n")
                        .append("$__step_headers = @{}\n");
                for (Map.Entry<String, String> header : request.headers().entrySet()) {
                    script.append("$__step_headers[")
                            .append(psSingleQuoted(header.getKey())).append("] = Resolve-McpJvmTemplate ")
                            .append(psSingleQuoted(header.getValue())).append("\n");
                }
                script.append("$__step_request = @{ Method = ")
                        .append(psSingleQuoted(request.method()))
                        .append("; Uri = $__step_url; Headers = $__step_headers; UseBasicParsing = $true }\n");
                if (request.body() != null) {
                    script.append("$__step_raw_body = ")
                            .append(psSingleQuoted(request.body())).append("\n")
                            .append("$__step_request.Body = Resolve-McpJvmTemplate $__step_raw_body\n");
                }
                script.append("$__step_response = Invoke-WebRequest @__step_request\n")
                        .append("if ($__step_response.StatusCode -lt 200 -or $__step_response.StatusCode -ge 300) { throw ")
                        .append(psSingleQuoted("workload_step_failed:" + request.stepId())).append(" }\n")
                        .append("Write-Host ")
                        .append(psSingleQuoted("[" + request.planName() + ":" + request.stepId() + "] status="))
                        .append("$__step_response.StatusCode\n\n");
            }
        }
    }

    private String renderShell(
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options,
            JsonNode profile,
            List<ScriptInvocation> scripts) {
        StringBuilder script = new StringBuilder("#!/usr/bin/env bash\nset -euo pipefail\n")
                .append("# MCPJVM export when=").append(shellSingleQuoted(options.when() == null ? "default" : options.when())).append("\n")
                .append("# includeRuntimeStartup=").append(options.includeRuntimeStartup())
                .append(" includeHealthcheckGate=").append(options.includeHealthcheckGate())
                .append(" includeResolvedSecrets=").append(options.includeResolvedSecrets()).append("\n")
                .append("__MCPJVM_PROJECT_ENV=\"$PWD/project.env\"\n")
                .append("if [ -f \"$__MCPJVM_PROJECT_ENV\" ]; then set -a; . \"$__MCPJVM_PROJECT_ENV\"; set +a; fi\n")
                .append("resolve_mcpjvm_template() {\n")
                .append("  local value=\"$1\" key replacement\n")
                .append("  while [[ \"$value\" =~ \\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\} ]]; do\n")
                .append("    key=\"${BASH_REMATCH[1]}\"\n")
                .append("    replacement=\"${!key:-}\"\n")
                .append("    value=\"${value//\\{\\{$key\\}\\}/$replacement}\"\n")
                .append("  done\n  printf '%s' \"$value\"\n}\n\n");
        appendShellRuntime(script, options, profile);
        appendShellScripts(script, scripts);
        appendShellHealthchecks(script, options.workspace(), options.includeHealthcheckGate());
        for (ExecutionExportWorkload.PlanWorkload plan : workload.plans()) {
            script.append("echo ").append(shellSingleQuoted(
                    "[" + plan.suiteType() + ":" + plan.planName() + "] replay workload")).append("\n");
            for (ExecutionExportWorkload.WorkloadRequest request : plan.requests()) {
                script.append("__step_url=\"$(resolve_mcpjvm_template ")
                        .append(shellSingleQuoted(request.url())).append(")\"\n");
                if (request.body() != null) {
                    script.append("__step_body=\"$(resolve_mcpjvm_template ")
                            .append(shellSingleQuoted(request.body())).append(")\"\n");
                }
                script.append("curl --fail --silent --show-error --request ")
                        .append(shellSingleQuoted(request.method())).append(" \"$__step_url\"");
                for (Map.Entry<String, String> header : request.headers().entrySet()) {
                    script.append(" --header \"$(resolve_mcpjvm_template ")
                            .append(shellSingleQuoted(header.getKey() + ": " + header.getValue()))
                            .append(")\"");
                }
                if (request.body() != null) {
                    script.append(" --data-raw \"$__step_body\"");
                }
                script.append("\n")
                        .append("echo ").append(shellSingleQuoted(
                                "[" + request.planName() + ":" + request.stepId() + "] replayed")).append("\n\n");
            }
        }
        return script.toString();
    }

    private void writeReplayFile(Path path, String content) {
        try {
            support.jsonStore().writeText(path, content + "\n");
        } catch (ArtifactOperationException exception) {
            throw new ArtifactOperationException("execution_export_write_failed",
                    "Execution export could not be persisted");
        }
    }

    private JsonNode selectedProfile(JsonNode workspace, String profileName) {
        if (profileName == null || !workspace.path("executionProfiles").isArray()) {
            return null;
        }
        for (JsonNode profile : workspace.path("executionProfiles")) {
            if (profileName.equals(profile.path("executionProfile").asText(null))) {
                return profile;
            }
        }
        return null;
    }

    private void writeProjectEnv(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String exportId,
            ExecutionExportOptions options) {
        Map<String, String> values = new LinkedHashMap<>();
        String envFile = options.workspace().path("envFile").asText("").trim();
        if (!envFile.isBlank()) {
            Path source = workspace.paths().check(workspace.root().resolve(envFile));
            parseDotEnv(support.jsonStore().readText(source), values);
        }
        for (String envKey : options.contextBindings().values()) {
            values.putIfAbsent(envKey, System.getenv().getOrDefault(envKey, ""));
        }
        for (Map.Entry<String, String> value : options.contextValues().entrySet()) {
            String envKey = options.contextBindings().getOrDefault(
                    value.getKey(), environmentKey(value.getKey()));
            values.put(envKey, value.getValue());
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Runtime inputs for run-execution-profile export");
        lines.add(options.includeResolvedSecrets()
                ? "# SENSITIVE EXPORT: includeResolvedSecrets=true."
                : "# Secret-like values are blanked because includeResolvedSecrets=false.");
        if (options.when() != null) {
            lines.add("# when=" + dotenvValue(options.when()));
        }
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String value = !options.includeResolvedSecrets() && isSensitiveEnvKey(entry.getKey())
                    ? "" : entry.getValue();
            lines.add(entry.getKey() + "=" + dotenvValue(value));
        });
        Path target = workspace.paths().resolve(
                ".mcpjvm", projectName, "exports", exportId, "project.env");
        support.jsonStore().writeText(target, String.join("\n", lines) + "\n");
    }

    private List<ScriptInvocation> prepareScripts(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String exportId,
            JsonNode profile,
            ExecutionExportOptions options) {
        if (profile == null || !profile.path("scriptRefs").isArray()) {
            return List.of();
        }
        List<ScriptInvocation> invocations = new ArrayList<>();
        for (JsonNode reference : profile.path("scriptRefs")) {
            ScriptInvocation invocation = prepareScript(
                    workspace, projectName, exportId, options.workspace(), reference);
            if (invocation != null) {
                invocations.add(invocation);
            }
        }
        invocations.sort(java.util.Comparator.comparingInt(value -> phaseOrder(value.phase())));
        return invocations;
    }

    private ScriptInvocation prepareScript(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String exportId,
            JsonNode projectWorkspace,
            JsonNode reference) {
        String name = reference.isTextual()
                ? reference.asText().trim() : reference.path("name").asText("").trim();
        if (name.isBlank()) {
            return null;
        }
        JsonNode script = findScript(projectWorkspace, name);
        if (script == null) {
            throw new ArtifactOperationException(
                    "execution_export_script_missing", "Selected execution-profile script is unavailable");
        }
        String command = script.path("command").asText("").trim();
        if (command.isBlank()) {
            throw new ArtifactOperationException(
                    "execution_export_script_invalid", "Selected execution-profile script has no command");
        }
        String phase = reference.isObject() && reference.path("phase").isTextual()
                ? reference.path("phase").asText() : script.path("phase").asText("prePlan");
        String scriptRoot = "scripts/" + safeFileSegment(name);
        List<String> args = exportedScriptArgs(
                workspace, projectName, exportId, scriptRoot, script);
        String appdir = script.path("appdir").asText("").trim();
        if (!appdir.isBlank()) {
            appdir = workspace.paths().check(workspace.root().resolve(appdir)).toString();
        }
        return new ScriptInvocation(name, phase, command, args, appdir, stringMap(script.path("env")));
    }

    private List<String> exportedScriptArgs(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String exportId,
            String scriptRoot,
            JsonNode script) {
        List<String> args = new ArrayList<>();
        if (script.path("args").isArray()) {
            for (JsonNode arg : script.path("args")) {
                if (arg.isTextual()) {
                    args.add(arg.asText());
                }
            }
        }
        List<String> exported = new ArrayList<>();
        for (int index = 0; index < args.size(); index++) {
            String arg = args.get(index);
            if ("-File".equals(arg) && index + 1 < args.size()) {
                exported.add(arg);
                exported.add(copyScriptArg(workspace, projectName, exportId, scriptRoot, args.get(++index)));
            } else {
                exported.add(copyScriptArgIfFile(workspace, projectName, exportId, scriptRoot, arg));
            }
        }
        addEnvFileArg(exported, script.path("envFileArg").asText(""));
        return exported;
    }

    private static void addEnvFileArg(List<String> args, String rawEnvFileArg) {
        String envFileArg = rawEnvFileArg.trim();
        if (envFileArg.isBlank()) {
            return;
        }
        int envIndex = args.indexOf(envFileArg);
        if (envIndex >= 0 && envIndex + 1 < args.size()) {
            args.set(envIndex + 1, "__MCPJVM_PROJECT_ENV__");
        } else {
            args.add(envFileArg);
            args.add("__MCPJVM_PROJECT_ENV__");
        }
    }

    private JsonNode findScript(JsonNode workspace, String name) {
        for (JsonNode script : workspace.path("scripts")) {
            if (name.equals(script.path("name").asText(null))) {
                return script;
            }
        }
        return null;
    }

    private String copyScriptArgIfFile(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String exportId,
            String scriptRoot,
            String value) {
        String extension = value.toLowerCase(java.util.Locale.ROOT);
        if (!(extension.endsWith(".ps1") || extension.endsWith(".sh") || extension.endsWith(".bash")
                || extension.endsWith(".js") || extension.endsWith(".mjs") || extension.endsWith(".py"))) {
            return value;
        }
        return copyScriptArg(workspace, projectName, exportId, scriptRoot, value);
    }

    private String copyScriptArg(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String exportId,
            String scriptRoot,
            String value) {
        Path source = workspace.paths().check(workspace.root().resolve(value));
        if (!Files.isRegularFile(source)) {
            throw new ArtifactOperationException(
                    "execution_export_script_missing", "Referenced execution-profile script file is unavailable");
        }
        try {
            if (Files.size(source) > 4L * 1024L * 1024L) {
                throw new ArtifactOperationException(
                        "execution_export_script_too_large",
                        "Referenced execution-profile script exceeds the read limit");
            }
        } catch (java.io.IOException exception) {
            throw new ArtifactOperationException(
                    "execution_export_script_read_failed", "Referenced execution-profile script could not be read");
        }
        String fileName = safeFileSegment(source.getFileName().toString());
        Path target = workspace.paths().resolve(
                ".mcpjvm", projectName, "exports", exportId, "scripts",
                scriptRoot.substring(scriptRoot.indexOf('/') + 1), fileName);
        support.jsonStore().writeText(target, support.jsonStore().readText(source));
        return "__MCPJVM_SCRIPT__" + scriptRoot + "/" + fileName;
    }

    private void appendPowerShellRuntime(StringBuilder script, ExecutionExportOptions options, JsonNode profile) {
        if (!options.includeRuntimeStartup()) {
            script.append("Write-Host '[R00] runtime startup skipped by export options'\n");
            return;
        }
        JsonNode context = runtimeContext(options.workspace(), profile);
        if (context == null) {
            script.append("Write-Host '[R00] runtime startup skipped: no runtime context'\n");
            return;
        }
        if ("docker".equals(context.path("mode").asText()) && context.path("composeFile").isTextual()) {
            script.append("docker compose -f ").append(psSingleQuoted(context.path("composeFile").asText()))
                    .append(" up -d\nif ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }\n");
        }
        for (JsonNode startup : context.path("startups")) {
            String command = startup.path("command").asText("");
            if (command.isBlank()) {
                continue;
            }
            script.append("Write-Host ").append(psSingleQuoted("[R] " + startup.path("name").asText("startup"))).append("\n")
                    .append("& ").append(psSingleQuoted(command));
            for (JsonNode arg : startup.path("args")) {
                if (arg.isTextual()) {
                    script.append(" ").append(psSingleQuoted(arg.asText()));
                }
            }
            script.append("\nif ($LASTEXITCODE -ne 0) { throw 'runtime startup failed' }\n");
        }
    }

    private void appendShellRuntime(StringBuilder script, ExecutionExportOptions options, JsonNode profile) {
        if (!options.includeRuntimeStartup()) {
            script.append("echo '[R00] runtime startup skipped by export options'\n");
            return;
        }
        JsonNode context = runtimeContext(options.workspace(), profile);
        if (context == null) {
            script.append("echo '[R00] runtime startup skipped: no runtime context'\n");
            return;
        }
        if ("docker".equals(context.path("mode").asText()) && context.path("composeFile").isTextual()) {
            script.append("docker compose -f ").append(shellSingleQuoted(context.path("composeFile").asText()))
                    .append(" up -d\n");
        }
        for (JsonNode startup : context.path("startups")) {
            String command = startup.path("command").asText("");
            if (command.isBlank()) {
                continue;
            }
            script.append("echo ").append(shellSingleQuoted("[R] " + startup.path("name").asText("startup"))).append("\n")
                    .append(shellSingleQuoted(command));
            for (JsonNode arg : startup.path("args")) {
                if (arg.isTextual()) {
                    script.append(" ").append(shellSingleQuoted(arg.asText()));
                }
            }
            script.append(" &\n");
        }
    }

    private void appendPowerShellHealthchecks(StringBuilder script, JsonNode workspace, boolean enabled) {
        if (!enabled) {
            script.append("Write-Host '[H00] healthcheck gate skipped by export options'\n");
            return;
        }
        for (JsonNode system : workspace.path("externalSystems")) {
            for (JsonNode check : system.path("healthChecks")) {
                String id = check.path("id").asText("healthcheck");
                if ("http".equals(check.path("type").asText()) && check.path("url").isTextual()) {
                    script.append("Write-Host ").append(psSingleQuoted("[H] " + id)).append("\n")
                            .append("$__health = Invoke-WebRequest -UseBasicParsing -Uri ")
                            .append(psSingleQuoted(check.path("url").asText())).append(" -TimeoutSec 5\n")
                            .append("if ($__health.StatusCode -lt 200 -or $__health.StatusCode -ge 500) { throw 'healthcheck gate failed' }\n");
                }
                if ("tcp".equals(check.path("type").asText()) && check.path("target").isTextual()) {
                    String[] target = check.path("target").asText().split(":", 2);
                    if (target.length == 2) {
                        script.append("if (-not (Test-NetConnection -ComputerName ")
                                .append(psSingleQuoted(target[0])).append(" -Port ").append(target[1])
                                .append(" -WarningAction SilentlyContinue).TcpTestSucceeded) { throw 'healthcheck gate failed' }\n");
                    }
                }
            }
        }
    }

    private void appendShellHealthchecks(StringBuilder script, JsonNode workspace, boolean enabled) {
        if (!enabled) {
            script.append("echo '[H00] healthcheck gate skipped by export options'\n");
            return;
        }
        for (JsonNode system : workspace.path("externalSystems")) {
            for (JsonNode check : system.path("healthChecks")) {
                String id = check.path("id").asText("healthcheck");
                if ("http".equals(check.path("type").asText()) && check.path("url").isTextual()) {
                    script.append("echo ").append(shellSingleQuoted("[H] " + id)).append("\n")
                            .append("curl --fail --silent --show-error ")
                            .append(shellSingleQuoted(check.path("url").asText())).append(" >/dev/null\n");
                }
                if ("tcp".equals(check.path("type").asText()) && check.path("target").isTextual()) {
                    String[] target = check.path("target").asText().split(":", 2);
                    if (target.length == 2) {
                        script.append("timeout 5 bash -c ").append(shellSingleQuoted(
                                "</dev/tcp/" + target[0] + "/" + target[1])).append(" >/dev/null 2>&1\n");
                    }
                }
            }
        }
    }

    private void appendPowerShellScripts(StringBuilder script, List<ScriptInvocation> scripts) {
        for (ScriptInvocation invocation : scripts) {
            script.append("Write-Host ").append(psSingleQuoted("[S] " + invocation.phase() + " " + invocation.name())).append("\n")
                    .append("$__mcpjvm_script_env_file = $__mcpjvm_env_file\n");
            invocation.env().forEach((key, value) -> script.append("$env:").append(key).append(" = ")
                    .append(psSingleQuoted(value)).append("\n"));
            if (!invocation.appdir().isBlank()) {
                script.append("Push-Location -LiteralPath ").append(psSingleQuoted(invocation.appdir())).append("\n");
            }
            script.append("& ").append(psSingleQuoted(invocation.command()));
            for (String arg : invocation.args()) {
                script.append(" ").append(psArgument(arg));
            }
            script.append("\n$__mcpjvm_script_exit = $LASTEXITCODE\n");
            if (!invocation.appdir().isBlank()) {
                script.append("Pop-Location\n");
            }
            script.append("if ($__mcpjvm_script_exit -ne 0) { throw 'export script failed' }\n");
        }
    }

    private void appendShellScripts(StringBuilder script, List<ScriptInvocation> scripts) {
        for (ScriptInvocation invocation : scripts) {
            script.append("echo ").append(shellSingleQuoted(
                    "[S] " + invocation.phase() + " " + invocation.name())).append("\n")
                    .append("(");
            invocation.env().forEach((key, value) -> script.append("export ")
                    .append(key).append("=").append(shellSingleQuoted(value)).append("; "));
            if (!invocation.appdir().isBlank()) {
                script.append("cd ").append(shellSingleQuoted(invocation.appdir())).append(" && ");
            }
            script.append(shellSingleQuoted(invocation.command()));
            for (String arg : invocation.args()) {
                script.append(" ").append(shellArgument(arg));
            }
            script.append(")\n");
        }
    }

    private JsonNode runtimeContext(JsonNode workspace, JsonNode profile) {
        String requested = profile == null ? "" : profile.path("runtimeContextName").asText("");
        JsonNode fallback = null;
        for (JsonNode context : workspace.path("runtimeContexts")) {
            if (requested.equals(context.path("name").asText(""))) {
                return context;
            }
            if (fallback == null && context.path("autoStart").asBoolean(false)) {
                fallback = context;
            }
            if (fallback == null) {
                fallback = context;
            }
        }
        return fallback;
    }

    private static int phaseOrder(String phase) {
        return switch (phase) {
            case "preRuntime" -> 0;
            case "postRuntime" -> 1;
            case "postHealthcheck" -> 2;
            default -> 3;
        };
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) {
                    values.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return values;
    }

    private static String psArgument(String value) {
        if ("__MCPJVM_PROJECT_ENV__".equals(value)) {
            return "$__mcpjvm_env_file";
        }
        if (value.startsWith("__MCPJVM_SCRIPT__")) {
            return "(Join-Path $PSScriptRoot " + psSingleQuoted(value.substring("__MCPJVM_SCRIPT__".length())) + ")";
        }
        return psSingleQuoted(value);
    }

    private static String shellArgument(String value) {
        if ("__MCPJVM_PROJECT_ENV__".equals(value)) {
            return "\"$__MCPJVM_PROJECT_ENV\"";
        }
        if (value.startsWith("__MCPJVM_SCRIPT__")) {
            return "\"$PWD/" + value.substring("__MCPJVM_SCRIPT__".length()) + "\"";
        }
        return shellSingleQuoted(value);
    }

    private static void parseDotEnv(String text, Map<String, String> values) {
        for (String raw : text.replace("\r", "").split("\n")) {
            String line = raw.trim();
            int separator = line.indexOf('=');
            if (line.isBlank() || line.startsWith("#") || separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                String value = line.substring(separator + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        }
    }

    private static String dotenvValue(String value) {
        if (value == null || value.matches("[A-Za-z0-9_./:@-]*")) {
            return value == null ? "" : value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean isSensitiveEnvKey(String key) {
        return key.matches("(?i).*(AUTH|BEARER|TOKEN|SECRET|PASSWORD|CREDENTIAL|USERNAME).*");
    }

    private static String environmentKey(String value) {
        return value.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static String safeFileSegment(String value) {
        String result = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return result.isBlank() ? "script" : result;
    }

    private record ScriptInvocation(
            String name,
            String phase,
            String command,
            List<String> args,
            String appdir,
            Map<String, String> env) {
    }

    private record ExportContext(
            ArtifactManagementSupport.Workspace workspace,
            String projectName,
            String exportId,
            Path export,
            String mode,
            ExecutionExportWorkload.Workload workload,
            ExecutionExportOptions options,
            JsonNode profile) {
    }

    private static java.util.Optional<String> optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? java.util.Optional.of(value.asText().trim()) : java.util.Optional.empty();
    }

    private static String replayFileName(String mode) {
        return switch (mode) {
            case "ps1" -> "replay.ps1";
            case "sh" -> "replay.sh";
            default -> "replay.postman.json";
        };
    }

    private static String stableExportId(String projectName, String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((projectName + "\n" + input).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("sha256-");
            for (int index = 0; index < 8; index++) {
                value.append(String.format("%02x", digest[index]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new ArtifactOperationException("execution_export_id_failed",
                    "export identifier could not be created");
        }
    }

    private String postmanCollection(
            ExecutionExportWorkload.Workload workload, ExecutionExportOptions options) {
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

    private static String psSingleQuoted(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String shellSingleQuoted(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void addStringMap(
            ObjectNode manifest, String field, Map<String, String> values) {
        ObjectNode output = manifest.putObject(field);
        values.forEach(output::put);
    }

    private static void addStringMapKeys(
            ObjectNode manifest, String field, Map<String, String> values) {
        ObjectNode output = manifest.putObject(field);
        values.keySet().forEach(key -> output.put(key, "[REDACTED]"));
    }
}
