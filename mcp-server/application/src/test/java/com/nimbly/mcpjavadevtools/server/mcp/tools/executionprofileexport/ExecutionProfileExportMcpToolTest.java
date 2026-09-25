package com.nimbly.mcpjavadevtools.server.mcp.tools.executionprofileexport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportArtifactInputMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExportExecutionProfileOperation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryException;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryExecutor;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailure;
import com.nimbly.mcpjavadevtools.server.mcp.error.McpBoundaryFailureKind;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponse;
import com.nimbly.mcpjavadevtools.server.mcp.tools.action.McpActionResponseMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class ExecutionProfileExportMcpToolTest {

    @Test
    void mapsThePublicRequestAndResponseWithoutAddingCapabilityBehavior() {
        ExecutionProfileExportFeature feature = request -> new ExecutionProfileExportResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(),
                Map.of("mode", request.mode(), "exportId", "export-1"));
        ExecutionProfileExportMcpTool tool = tool(feature);

        McpActionResponse response = tool.execute(new ExecutionProfileExportMcpRequest(
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
        ExecutionProfileExportMcpTool tool = tool(feature);

        McpActionResponse response = tool.execute(null);

        assertThat(response.resultType()).isEqualTo("report");
        assertThat(response.reasonCode()).isEqualTo("execution_profile_export_request_invalid");
    }

    @Test
    void containsInvalidRequestResponseMapperFailureForTypedInput() {
        ExecutionProfileExportMcpTool tool = tool(
                request -> ExecutionProfileExportResult.invalidRequest(),
                failingInvalidRequestMapper());

        McpActionResponse response = tool.execute(null);

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta())
                .containsEntry("failedStep", McpBoundaryFailureKind.RESPONSE_MAPPING.value());
        assertThat(response.reason()).doesNotContain("secret-value");
    }

    @Test
    void containsInvalidRequestResponseMapperFailureForMalformedRawJson() throws Exception {
        ExecutionProfileExportMcpTool tool = tool(
                request -> ExecutionProfileExportResult.invalidRequest(),
                failingInvalidRequestMapper());

        JsonNode response = new ObjectMapper().readTree(tool.call("{"));

        assertThat(response.path("reasonCode").asText()).isEqualTo("internal_error");
        assertThat(response.path("reasonMeta").path("failedStep").asText())
                .isEqualTo(McpBoundaryFailureKind.RESPONSE_MAPPING.value());
        assertThat(response.toString()).doesNotContain("secret-value");
    }

    @Test
    void mapsFeatureIllegalArgumentExceptionToTheDeterministicInvalidRequest() {
        ExecutionProfileExportMcpTool tool = tool(request -> {
            throw new IllegalArgumentException("request is not acceptable");
        });

        McpActionResponse response = tool.execute(request());

        assertThat(response.reasonCode()).isEqualTo("execution_profile_export_request_invalid");
        assertThat(response.status()).isEqualTo("execution_profile_export_request_invalid");
    }

    @Test
    void containsUnexpectedRequestMappingFailuresWithoutLeakingDetails() {
        Map<String, String> failingBindings = new HashMap<>() {
            @Override
            public void forEach(BiConsumer<? super String, ? super String> action) {
                throw new IllegalStateException("Authorization: Bearer secret-value");
            }
        };
        failingBindings.put("authorization", "secret-value");

        ExecutionProfileExportMcpTool tool = tool(request -> {
            throw new AssertionError("Core Feature must not receive a failed request mapping");
        });
        McpActionResponse response = tool.execute(new ExecutionProfileExportMcpRequest(
                "demo", "export-1", "nightly", "smoke", null, "sh", null,
                false, true, true, failingBindings, Map.of()));

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta())
                .containsEntry("failedStep", McpBoundaryFailureKind.FEATURE_INVOCATION_CONTRACT.value());
        assertThat(response.reason()).doesNotContain("secret-value");
    }

    @Test
    void containsUnexpectedFeatureFailuresWithoutLeakingDetails() {
        ExecutionProfileExportMcpTool tool = tool(request -> {
            throw new IllegalStateException("Authorization: Bearer secret-value");
        });

        McpActionResponse response = tool.execute(request());

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta())
                .containsEntry("failedStep", McpBoundaryFailureKind.FEATURE_INVOCATION_CONTRACT.value());
        assertThat(response.reason()).doesNotContain("secret-value");
    }

    @Test
    void preservesClassifiedFeatureBoundaryFailures() {
        ExecutionProfileExportMcpTool tool = tool(request -> {
            throw new McpBoundaryException(
                    McpBoundaryFailureKind.CONFIGURATION_INVARIANT,
                    new IllegalStateException("secret-value"));
        });

        McpActionResponse response = tool.execute(request());

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta())
                .containsEntry("failedStep", McpBoundaryFailureKind.CONFIGURATION_INVARIANT.value());
        assertThat(response.reason()).doesNotContain("secret-value");

        JsonNode rawResponse;
        try {
            rawResponse = new ObjectMapper().readTree(tool.call("{}"));
        } catch (Exception exception) {
            throw new AssertionError("controlled boundary response was not valid JSON", exception);
        }
        assertThat(rawResponse.path("reasonCode").asText()).isEqualTo("internal_error");
        assertThat(rawResponse.path("reasonMeta").path("failedStep").asText())
                .isEqualTo(McpBoundaryFailureKind.CONFIGURATION_INVARIANT.value());
        assertThat(rawResponse.toString()).doesNotContain("secret-value");
    }

    @Test
    void containsUnexpectedResponseMappingFailuresWithoutLeakingDetails() {
        ExecutionProfileExportMcpTool tool = tool(request -> null);

        McpActionResponse response = tool.execute(request());

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta())
                .containsEntry("failedStep", McpBoundaryFailureKind.RESPONSE_MAPPING.value());
        assertThat(response.reason()).doesNotContain("NullPointerException");
    }

    @Test
    void preservesClassifiedResponseMappingFailures() {
        McpActionResponseMapper<ExecutionProfileExportRequest, ExecutionProfileExportResult> mapper =
                new McpActionResponseMapper<>() {
            @Override
            public McpActionResponse map(
                    ExecutionProfileExportRequest request, ExecutionProfileExportResult result) {
                throw new McpBoundaryException(
                        McpBoundaryFailureKind.CONFIGURATION_INVARIANT,
                        new IllegalStateException("secret-value"));
            }

            @Override
            public McpActionResponse invalidRequest() {
                return new ExecutionProfileExportMcpResponseMapper().invalidRequest();
            }

            @Override
            public McpActionResponse mapBoundary(McpBoundaryFailure failure) {
                return new ExecutionProfileExportMcpResponseMapper().mapBoundary(failure);
            }
        };
        ExecutionProfileExportMcpTool tool = tool(request -> new ExecutionProfileExportResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(), Map.of()), mapper);

        McpActionResponse response = tool.execute(request());

        assertThat(response.reasonCode()).isEqualTo("internal_error");
        assertThat(response.reasonMeta())
                .containsEntry("failedStep", McpBoundaryFailureKind.CONFIGURATION_INVARIANT.value());
        assertThat(response.reason()).doesNotContain("secret-value");
    }

    @Test
    void returnsDeterministicInvalidRequestForMalformedRawJson() throws Exception {
        ExecutionProfileExportMcpTool tool = tool(request -> ExecutionProfileExportResult.invalidRequest());

        String response = tool.call("{");

        assertThat(new ObjectMapper().readTree(response).path("reasonCode").asText())
                .isEqualTo("execution_profile_export_request_invalid");
    }

    @Test
    void returnsTheBoundedFallbackWhenResponseSerializationFails() {
        Map<String, Object> recursiveDetails = new HashMap<>();
        recursiveDetails.put("self", recursiveDetails);
        ExecutionProfileExportMcpTool tool = tool(request -> new ExecutionProfileExportResult(
                "execution_profile_export", "ok", "success", null, null, "", Map.of(), recursiveDetails));

        assertThat(tool.call("{}"))
                .isEqualTo("{\"resultType\":\"report\",\"status\":\"internal_error\","
                        + "\"reasonCode\":\"internal_error\"}");
    }

    @Test
    void hasNoPrivateWorkflowOrSerializationMethods() {
        assertThat(Arrays.stream(ExecutionProfileExportMcpTool.class.getDeclaredMethods())
                .filter(method -> Modifier.isPrivate(method.getModifiers()))
                .map(Method::getName)
                .toList()).isEmpty();
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
        assertThat(entry.action()).isEmpty();
        assertThat(entry.actionless()).isTrue();
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
                .containsExactly(descriptor.action());
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
                catalog, new ObjectMapper());

        McpActionResponse response = tool.execute(new ExecutionProfileExportMcpRequest(
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

    private ExecutionProfileExportMcpTool tool(ExecutionProfileExportFeature feature) {
        return tool(feature, new ExecutionProfileExportMcpResponseMapper());
    }

    private ExecutionProfileExportMcpTool tool(
            ExecutionProfileExportFeature feature,
            McpActionResponseMapper<ExecutionProfileExportRequest, ExecutionProfileExportResult> responseMapper) {
        return new ExecutionProfileExportMcpTool(
                feature,
                new ExecutionProfileExportMcpRequestMapper(),
                responseMapper,
                new McpBoundaryExecutor(),
                new ObjectMapper());
    }

    private McpActionResponseMapper<ExecutionProfileExportRequest, ExecutionProfileExportResult>
            failingInvalidRequestMapper() {
        return new McpActionResponseMapper<>() {
            @Override
            public McpActionResponse map(
                    ExecutionProfileExportRequest request, ExecutionProfileExportResult result) {
                return new ExecutionProfileExportMcpResponseMapper().map(request, result);
            }

            @Override
            public McpActionResponse invalidRequest() {
                throw new IllegalStateException("secret-value");
            }

            @Override
            public McpActionResponse mapBoundary(McpBoundaryFailure failure) {
                return new ExecutionProfileExportMcpResponseMapper().mapBoundary(failure);
            }
        };
    }

    private ExecutionProfileExportMcpRequest request() {
        return new ExecutionProfileExportMcpRequest(
                "demo", "export-1", "nightly", "smoke", null, "sh", null,
                false, true, true, Map.of(), Map.of());
    }
}
