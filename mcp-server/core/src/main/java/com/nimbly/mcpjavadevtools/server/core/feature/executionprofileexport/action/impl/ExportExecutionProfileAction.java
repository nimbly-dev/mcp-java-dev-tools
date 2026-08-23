package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action.ExecutionProfileExportActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import java.util.Map;

/** Delegates export generation through the approved Artifact boundary. */
public final class ExportExecutionProfileAction implements ExecutionProfileExportActionHandler {

    private final ExecutionExportArtifactGateway artifactGateway;
    private final ObjectMapper objectMapper;

    /** Creates the export action with its Artifact boundary collaborator. */
    public ExportExecutionProfileAction(
            ExecutionExportArtifactGateway artifactGateway, ObjectMapper objectMapper) {
        this.artifactGateway = artifactGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ExecutionProfileExportAction action() {
        return ExecutionProfileExportAction.EXPORT;
    }

    @Override
    public ExecutionProfileExportResult execute(ExecutionProfileExportRequest request) {
        ArtifactManagementResult result = artifactGateway.generate(new ArtifactManagementRequest(
                ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE, toArtifactInput(request)));
        return ExecutionProfileExportResult.fromArtifactResult(result);
    }

    private ObjectNode toArtifactInput(ExecutionProfileExportRequest request) {
        ObjectNode input = objectMapper.createObjectNode();
        putText(input, "projectName", request.projectName());
        putText(input, "exportId", request.exportId());
        putText(input, "executionProfile", request.executionProfile());
        putText(input, "planName", request.planName());
        putText(input, "when", request.when());
        putText(input, "mode", request.mode());
        putText(input, "type", request.type());
        putBoolean(input, "includeResolvedSecrets", request.includeResolvedSecrets());
        putBoolean(input, "includeRuntimeStartup", request.includeRuntimeStartup());
        putBoolean(input, "includeHealthcheckGate", request.includeHealthcheckGate());
        putMap(input, "contextBindings", request.contextBindings());
        putMap(input, "contextValues", request.contextValues());
        return input;
    }

    private void putText(ObjectNode input, String field, String value) {
        if (value != null && !value.isBlank()) {
            input.put(field, value);
        }
    }

    private void putBoolean(ObjectNode input, String field, Boolean value) {
        if (value != null) {
            input.put(field, value);
        }
    }

    private void putMap(ObjectNode input, String field, Map<String, String> value) {
        if (value != null && !value.isEmpty()) {
            input.set(field, objectMapper.valueToTree(value));
        }
    }
}
