package com.nimbly.mcpjavadevtools.server.mcp.tools.artifactmanagement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import java.util.Map;

/** Maps only the structural MCP envelope into the Core request contract. */
public final class ArtifactManagementMcpRequestMapper {

    private final ObjectMapper mapper;

    /** Creates a mapper using the Application Jackson mapper. */
    public ArtifactManagementMcpRequestMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Validates the closed family/action discriminators and converts input JSON. */
    public ArtifactManagementRequest map(ArtifactManagementMcpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Artifact Management request is required");
        }
        ArtifactType artifactType = ArtifactType.fromValue(request.artifactType())
                .orElseThrow(() -> new IllegalArgumentException("Artifact type is not supported"));
        ArtifactAction action = ArtifactAction.fromValue(request.action())
                .orElseThrow(() -> new IllegalArgumentException("Artifact action is not supported"));
        Map<String, Object> input = request.input() == null ? Map.of() : request.input();
        JsonNode inputNode = mapper.valueToTree(input);
        return new ArtifactManagementRequest(artifactType, action, inputNode);
    }
}
