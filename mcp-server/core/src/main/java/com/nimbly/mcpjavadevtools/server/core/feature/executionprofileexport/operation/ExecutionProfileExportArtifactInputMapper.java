package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import java.util.Objects;

/** Maps the typed export request to the approved Artifact input shape. */
public class ExecutionProfileExportArtifactInputMapper {

    private final ObjectMapper objectMapper;

    /** Creates the purpose-owned Artifact input mapper. */
    public ExecutionProfileExportArtifactInputMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Preserves the released export input omission and value rules.
     *
     * @param request typed export request
     * @return Artifact-bound input object
     */
    public ObjectNode map(ExecutionProfileExportRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ObjectNode input = objectMapper.createObjectNode();
        if (request.projectName() != null && !request.projectName().isBlank()) {
            input.put("projectName", request.projectName());
        }
        if (request.exportId() != null && !request.exportId().isBlank()) {
            input.put("exportId", request.exportId());
        }
        if (request.executionProfile() != null && !request.executionProfile().isBlank()) {
            input.put("executionProfile", request.executionProfile());
        }
        if (request.planName() != null && !request.planName().isBlank()) {
            input.put("planName", request.planName());
        }
        if (request.when() != null && !request.when().isBlank()) {
            input.put("when", request.when());
        }
        if (request.mode() != null && !request.mode().isBlank()) {
            input.put("mode", request.mode());
        }
        if (request.type() != null && !request.type().isBlank()) {
            input.put("type", request.type());
        }
        if (request.includeResolvedSecrets() != null) {
            input.put("includeResolvedSecrets", request.includeResolvedSecrets());
        }
        if (request.includeRuntimeStartup() != null) {
            input.put("includeRuntimeStartup", request.includeRuntimeStartup());
        }
        if (request.includeHealthcheckGate() != null) {
            input.put("includeHealthcheckGate", request.includeHealthcheckGate());
        }
        if (request.contextBindings() != null && !request.contextBindings().isEmpty()) {
            input.set("contextBindings", objectMapper.valueToTree(request.contextBindings()));
        }
        if (request.contextValues() != null && !request.contextValues().isEmpty()) {
            input.set("contextValues", objectMapper.valueToTree(request.contextValues()));
        }
        return input;
    }
}
