package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action.impl.ExportExecutionProfileAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
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
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(List.of(
                new ExportExecutionProfileAction(gateway, new ObjectMapper())));

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
        ExecutionProfileExportFeature feature = new DefaultExecutionProfileExportFeature(List.of(
                new ExportExecutionProfileAction(gateway, new ObjectMapper())));

        var result = feature.execute(null);

        assertThat(result.resultType()).isEqualTo("report");
        assertThat(result.reasonCode()).isEqualTo("execution_profile_export_request_invalid");
    }
}
