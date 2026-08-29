package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.util.ArrayList;
import java.util.List;

/** Maps profile script arguments to portable export-package paths. */
final class ExecutionExportScriptArguments {

    private final ExecutionExportScriptFileCopier copier;

    ExecutionExportScriptArguments(ArtifactManagementSupport support) {
        copier = new ExecutionExportScriptFileCopier(support);
    }

    List<String> copy(ExecutionExportContext context, String scriptRoot, JsonNode script) {
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
                exported.add(copier.copy(context, scriptRoot, args.get(++index)));
            } else {
                exported.add(copier.copyIfFile(context, scriptRoot, arg));
            }
        }
        String envFileArg = script.path("envFileArg").asText("").trim();
        if (!envFileArg.isBlank()) {
            int envIndex = exported.indexOf(envFileArg);
            if (envIndex >= 0 && envIndex + 1 < exported.size()) {
                exported.set(envIndex + 1, "__MCPJVM_PROJECT_ENV__");
            } else {
                exported.add(envFileArg);
                exported.add("__MCPJVM_PROJECT_ENV__");
            }
        }
        return exported;
    }
}
