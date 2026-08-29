package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.DefaultExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class ExecutionProfileExportMcpToolTest {

    @Test
    void mapsThePublicRequestAndResponseWithoutAddingCapabilityBehavior() {
        ExecutionProfileExportFeature feature = request -> new ExecutionProfileExportResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(),
                Map.of("mode", request.mode(), "exportId", "export-1"));
        ExecutionProfileExportMcpTool tool = new ExecutionProfileExportMcpTool(
                feature, new ExecutionProfileExportMcpRequestMapper(),
                new ExecutionProfileExportMcpResponseMapper(),
                new com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor(),
                new com.fasterxml.jackson.databind.ObjectMapper());

        McpActionResponse response = tool.invokeMcpRequest(new ExecutionProfileExportMcpRequest(
                "demo", null, "nightly", null, null, "sh", null, false, true, true,
                Map.of("auth.bearer", "AUTH_TOKEN"), Map.of()));

        assertThat(response.resultType()).isEqualTo("execution_profile_export");
        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.details()).containsEntry("mode", "sh");
        assertThat(response.details()).containsEntry("exportId", "export-1");
    }

    @Test
    void returnsDeterministicInvalidRequestForNullTransportInput() {
        ExecutionProfileExportFeature feature = request -> ExecutionProfileExportResult.invalidRequest();
        ExecutionProfileExportMcpTool tool = new ExecutionProfileExportMcpTool(feature);

        McpActionResponse response = tool.invokeMcpRequest(null);

        assertThat(response.resultType()).isEqualTo("report");
        assertThat(response.reasonCode()).isEqualTo("execution_profile_export_request_invalid");
    }

    @Test
    void tiesCatalogExposureToTheActualMcpToolRegistration() throws NoSuchMethodException {
        McpTool registration = ExecutionProfileExportMcpTool.class
                .getMethod("execute", ExecutionProfileExportMcpRequest.class)
                .getAnnotation(McpTool.class);
        OperationExposure exposure = ExecutionProfileExportMcpTool.operationExposure();

        assertThat(registration).isNotNull();
        assertThat(registration.name()).isEqualTo(exposure.toolName());
        assertThat(exposure.adapterType()).isEqualTo(ExecutionProfileExportMcpTool.class.getName());
        assertThat(exposure.actions()).containsExactly("export");
    }

    @Test
    void generatesCompleteInventoryFromTheCompiledCatalogAndMcpExposure() throws NoSuchMethodException {
        ExecutionProfileExportOperationCatalog catalog = operationCatalog();
        OperationDescriptor descriptor = catalog.describe(ExecutionProfileExportAction.EXPORT);
        OperationTraceEntry entry = catalog.traceInventory().getFirst();
        McpTool registration = ExecutionProfileExportMcpTool.class
                .getMethod("execute", ExecutionProfileExportMcpRequest.class)
                .getAnnotation(McpTool.class);

        assertThat(catalog.catalog()).containsExactly(descriptor);
        assertThat(entry.toolName()).isEqualTo(registration.name()).isEqualTo(descriptor.toolName());
        assertThat(entry.action()).isEqualTo(descriptor.action());
        assertThat(entry.requestType()).isEqualTo(descriptor.requestType());
        assertThat(entry.resultType()).isEqualTo(descriptor.resultType());
        assertThat(entry.mcpAdapter()).isEqualTo(descriptor.trace().mcpAdapter());
        assertThat(entry.requestMapper()).isEqualTo(descriptor.trace().requestMapper());
        assertThat(entry.coreFeature()).isEqualTo(descriptor.trace().coreFeature());
        assertThat(entry.operationCatalog()).isEqualTo(ExecutionProfileExportOperationCatalog.class.getName());
        assertThat(entry.descriptorType()).isEqualTo(OperationDescriptor.class.getName());
        assertThat(entry.executableOwner()).isEqualTo(descriptor.executableOwner());
        assertThat(entry.responseMapper()).isEqualTo(descriptor.trace().responseMapper());
        assertThat(entry.focusedEvidence()).isEqualTo(descriptor.trace().focusedEvidence());
        assertThat(entry.sideEffect()).isEqualTo(descriptor.trace().sideEffect());
        assertThat(entry.collaboratorRoles()).isEqualTo(descriptor.trace().collaboratorRoles());
        assertThat(ExecutionProfileExportMcpTool.operationExposure().actions())
                .containsExactly(entry.action());
    }

    @Test
    void dispatchesTheMcpActionThroughTheFeatureCatalogToTheConcreteOperation() {
        AtomicReference<ArtifactManagementRequest> captured = new AtomicReference<>();
        ExecutionProfileExportOperationCatalog catalog = operationCatalog(request -> {
            captured.set(request);
            return new ArtifactManagementResult(
                    "execution_profile_export", "ok", "success", null, null, "", Map.of(), Map.of());
        });
        ExecutionProfileExportMcpTool tool = new ExecutionProfileExportMcpTool(
                new DefaultExecutionProfileExportFeature(catalog));

        McpActionResponse response = tool.invokeMcpRequest(new ExecutionProfileExportMcpRequest(
                "demo", "export-1", "nightly", "smoke", null, "sh", "sh",
                false, true, true, Map.of(), Map.of()));

        assertThat(response.resultType()).isEqualTo("execution_profile_export");
        assertThat(response.status()).isEqualTo("ok");
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().artifactType().value()).isEqualTo("execution_export");
        assertThat(captured.get().action().value()).isEqualTo("generate");
    }

    private ExecutionProfileExportOperationCatalog operationCatalog() {
        return operationCatalog(request -> null);
    }

    private ExecutionProfileExportOperationCatalog operationCatalog(ExecutionExportArtifactGateway gateway) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ExecutionProfileExportOperationCatalog(
                new ExportExecutionProfileOperation(
                        gateway,
                        new ExecutionProfileExportArtifactInputMapper(objectMapper),
                        new OperationTraceMetadata(
                                ExecutionProfileExportMcpTool.class.getName(),
                                ExecutionProfileExportMcpRequestMapper.class.getName(),
                                ExecutionProfileExportFeature.class.getName(),
                                ExecutionProfileExportMcpResponseMapper.class.getName(),
                                ExecutionProfileExportMcpTypeScriptParityFixtureTest.class.getName(),
                                "filesystem_artifact_export",
                                Map.of(
                                        "artifactGateway", ExecutionExportArtifactGateway.class.getName(),
                                        "artifactInputMapper", ExecutionProfileExportArtifactInputMapper.class
                                                .getName()))),
                ExecutionProfileExportMcpTool.operationExposure());
    }
}
