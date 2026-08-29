package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactOperationException;
import java.util.List;

/** Resolves one execution-profile script reference into a copied invocation. */
final class ExecutionExportScriptReference {

    private final ExecutionExportScriptArguments arguments;

    ExecutionExportScriptReference(ExecutionExportScriptArguments arguments) {
        this.arguments = arguments;
    }

    ExecutionExportScriptInvocation resolve(
            ExecutionExportContext context, JsonNode reference) {
        String name = reference.isTextual()
                ? reference.asText().trim() : reference.path("name").asText("").trim();
        if (name.isBlank()) {
            return null;
        }
        JsonNode script = null;
        for (JsonNode candidate : context.options().workspace().path("scripts")) {
            if (name.equals(candidate.path("name").asText(null))) {
                script = candidate;
                break;
            }
        }
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
        String scriptRoot = "scripts/" + ExecutionExportScriptFileName.safe(name);
        List<String> copiedArguments = arguments.copy(context, scriptRoot, script);
        String appdir = script.path("appdir").asText("").trim();
        if (!appdir.isBlank()) {
            appdir = context.workspace().paths().check(context.workspace().root().resolve(appdir)).toString();
        }
        return new ExecutionExportScriptInvocation(
                name, phase, command, copiedArguments, appdir,
                ExecutionExportScalarValues.from(script.path("env")));
    }
}
