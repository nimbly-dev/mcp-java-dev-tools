package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultExecutionProfileExportFeatureTest {

    @Test
    void dispatchesTheCompleteExportAllowlist() {
        ExecutionExportArtifactGateway gateway = request ->
                new com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult(
                        "execution_profile_export", "ok", "success", null, null, "", Map.of(),
                        Map.of("exportId", "export-1"));
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(catalog(gateway));

        var result = feature.execute(new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT, null, null, null, null, null,
                "sh", null, null, null, null, Map.of(), Map.of()));

        assertThat(result.status()).isEqualTo("ok");
        assertThat(result.details()).containsEntry("exportId", "export-1");
    }

    @Test
    void returnsDeterministicInvalidRequestForMissingFeatureRequest() {
        ExecutionExportArtifactGateway gateway = request ->
                new com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult(
                        "execution_profile_export", "ok", "success", null, null, "", Map.of(), Map.of());
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(catalog(gateway));

        var result = feature.execute(null);

        assertThat(result.resultType()).isEqualTo("report");
        assertThat(result.reasonCode()).isEqualTo("execution_profile_export_request_invalid");
    }

    @Test
    void exposesCompleteCatalogAndGeneratedTraceInventory() {
        ExecutionProfileExportOperationCatalog catalog = catalog(request -> null);

        assertThat(catalog.catalog()).hasSize(1);
        OperationDescriptor descriptor = catalog.describe(ExecutionProfileExportAction.EXPORT);
        assertThat(descriptor.toolName()).isEqualTo("execution_profile_export");
        assertThat(descriptor.action()).isEqualTo("export");
        assertThat(descriptor.requestType()).contains("ExecutionProfileExportRequest");
        assertThat(descriptor.resultType()).contains("ExecutionProfileExportResult");
        assertThat(catalog.traceInventory()).hasSize(1);
        assertThat(catalog.traceInventory().getFirst().executableOwner())
                .isEqualTo(descriptor.executableOwner());
    }

    private ExecutionProfileExportOperationCatalog catalog(ExecutionExportArtifactGateway gateway) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ExecutionProfileExportOperationCatalog(
                new ExportExecutionProfileOperation(
                        gateway,
                        new ExecutionProfileExportArtifactInputMapper(objectMapper),
                        new OperationTraceMetadata(
                                "ExecutionProfileExportMcpTool",
                                "ExecutionProfileExportMcpRequestMapper",
                                "DefaultExecutionProfileExportFeature",
                                "ExecutionProfileExportMcpResponseMapper",
                                "DefaultExecutionProfileExportFeatureTest",
                                "filesystem_artifact_export",
                                Map.of(
                                        "artifactGateway", ExecutionExportArtifactGateway.class.getName(),
                                        "artifactInputMapper", ExecutionProfileExportArtifactInputMapper.class.getName()))),
                new OperationExposure(
                        "execution_profile_export", "ExecutionProfileExportMcpTool", List.of("export")));
    }
}
