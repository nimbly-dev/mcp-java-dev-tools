package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.ArtifactManagementSupport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves profile script references and prepares portable script invocations. */
final class ExecutionExportScriptPreparation {

    private final ExecutionExportScriptReference reference;

    ExecutionExportScriptPreparation(ArtifactManagementSupport support) {
        reference = new ExecutionExportScriptReference(
                new ExecutionExportScriptArguments(support));
    }

    List<ExecutionExportScriptInvocation> prepare(ExecutionExportContext context) {
        JsonNode profile = context.profile();
        if (profile == null || !profile.path("scriptRefs").isArray()) {
            return List.of();
        }
        List<ExecutionExportScriptInvocation> invocations = new ArrayList<>();
        for (JsonNode scriptReference : profile.path("scriptRefs")) {
            ExecutionExportScriptInvocation invocation = reference.resolve(context, scriptReference);
            if (invocation != null) {
                invocations.add(invocation);
            }
        }
        invocations.sort(Comparator.comparingInt(
                value -> ExecutionExportScriptPhase.order(value.phase())));
        return invocations;
    }
}
