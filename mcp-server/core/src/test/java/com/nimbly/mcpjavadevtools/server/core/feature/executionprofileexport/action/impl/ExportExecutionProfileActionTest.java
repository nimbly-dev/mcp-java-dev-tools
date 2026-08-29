package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExportExecutionProfileActionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsTheTypedRequestThroughTheApprovedArtifactGateway() {
        AtomicReference<ArtifactManagementRequest> captured = new AtomicReference<>();
        ExecutionExportArtifactGateway gateway = request -> {
            captured.set(request);
            return successResult();
        };
        ExportExecutionProfileOperation operation = operation(gateway);

        ExecutionProfileExportResult result = operation.execute(request());

        assertThat(operation.operationId()).isEqualTo(ExecutionProfileExportAction.EXPORT);
        assertThat(result.resultType()).isEqualTo("execution_profile_export");
        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.details()).containsEntry("exportId", "export-1");
        JsonNode input = captured.get().input();
        assertThat(captured.get().artifactType().value()).isEqualTo("execution_export");
        assertThat(captured.get().action().value()).isEqualTo("generate");
        assertThat(input.path("mode").asText()).isEqualTo("postman");
        assertThat(input.path("type").asText()).isEqualTo("postman");
        assertThat(input.path("contextBindings").path("auth.bearer").asText())
                .isEqualTo("AUTH_TOKEN");
        assertThat(input.path("contextValues").path("apiBaseUrl").asText())
                .isEqualTo("http://localhost");
    }

    @Test
    void preservesDeterministicBlockedArtifactOutcomes() {
        ExecutionExportArtifactGateway gateway = request -> new ArtifactManagementResult(
                "report", "execution_export_mode_required", "execution_export_mode_required",
                "provide_mode", "Provide mode.", "mode is required", Map.of("failedStep", "input"), Map.of());
        ExportExecutionProfileOperation operation = operation(gateway);

        ExecutionProfileExportResult result = operation.execute(request());

        assertThat(result.resultType()).isEqualTo("report");
        assertThat(result.status()).isEqualTo("execution_export_mode_required");
        assertThat(result.reasonCode()).isEqualTo("execution_export_mode_required");
        assertThat(result.reasonMeta()).containsEntry("failedStep", "input");
    }

    private ExportExecutionProfileOperation operation(ExecutionExportArtifactGateway gateway) {
        return new ExportExecutionProfileOperation(
                gateway,
                new ExecutionProfileExportArtifactInputMapper(objectMapper),
                new OperationTraceMetadata(
                        "ExecutionProfileExportMcpTool",
                        "ExecutionProfileExportMcpRequestMapper",
                        "DefaultExecutionProfileExportFeature",
                        "ExecutionProfileExportMcpResponseMapper",
                        "ExportExecutionProfileActionTest",
                        "filesystem_artifact_export",
                        Map.of(
                                "artifactGateway", ExecutionExportArtifactGateway.class.getName(),
                                "artifactInputMapper", ExecutionProfileExportArtifactInputMapper.class.getName())));
    }

    private ExecutionProfileExportRequest request() {
        return new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT, "demo", "export-1", "nightly", "smoke",
                "2026-08-21T12:00:00Z", "postman", "postman", true, false, true,
                Map.of("auth.bearer", "AUTH_TOKEN"), Map.of("apiBaseUrl", "http://localhost"));
    }

    private ArtifactManagementResult successResult() {
        return new ArtifactManagementResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(),
                Map.of("artifactType", "execution_export", "action", "generate",
                        "exportId", "export-1", "mode", "postman"));
    }
}
