package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDirectory;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationInvocation;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestLoader;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchemaValidator;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationProvenance;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExecutionProfileExportOperationRegistrationsTest {

    @Test
    void canonicalDirectoryCallsExactTypedExportOwnerOnceAndPreservesResultFields() {
        ObjectMapper mapper = new ObjectMapper();
        ExportExecutionProfileOperation owner = new ExportExecutionProfileOperation(
                request -> ArtifactManagementResult.success(
                        ArtifactType.EXECUTION_EXPORT, ArtifactAction.GENERATE,
                        Map.of("exportId", "fixture-export", "authorization", "fixture-secret")),
                new ExecutionProfileExportArtifactInputMapper(mapper),
                new OperationTraceMetadata("test", "test", "test", "test", "test",
                        "filesystem_artifact_export", Map.of("artifactGateway", "fixture")));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<ExecutionProfileExportRequest> received = new AtomicReference<>();
        Operation<ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult>
                observed = new Operation<>() {
                    @Override
                    public ExecutionProfileExportAction operationId() {
                        return owner.operationId();
                    }

                    @Override
                    public OperationDescriptor descriptor() {
                        return owner.descriptor();
                    }

                    @Override
                    public String executableOwner() {
                        return owner.executableOwner();
                    }

                    @Override
                    public OperationProvenance provenance() {
                        return owner.provenance();
                    }

                    @Override
                    public ExecutionProfileExportResult execute(ExecutionProfileExportRequest request) {
                        calls.incrementAndGet();
                        received.set(request);
                        return owner.execute(request);
                    }
                };
        OperationRegistration<?, ?> registration =
                ExecutionProfileExportOperationRegistrations.register(observed, mapper);
        OperationId id = OperationId.of("execution_profile_export.export");
        OperationManifestDocument all = OperationManifestLoader.loadBuiltIn();
        OperationDirectory directory = new OperationDirectory(List.of(registration),
                new OperationManifestDocument(all.version(), Map.of(id, all.operations().get(id))), mapper);
        var input = mapper.createObjectNode()
                .put("projectName", "demo").put("exportId", "fixture-export")
                .put("executionProfile", "regression-smoke").put("mode", "sh").put("type", "sh")
                .put("includeResolvedSecrets", false).put("includeRuntimeStartup", false)
                .put("includeHealthcheckGate", false);
        ExecutionProfileExportRequest expected = new ExecutionProfileExportRequest(
                ExecutionProfileExportAction.EXPORT, "demo", "fixture-export",
                "regression-smoke", null, null, "sh", "sh", false, false, false,
                Map.of(), Map.of());

        var result = directory.execute(new OperationInvocation(id, input, true));

        assertThat(result.status()).isEqualTo(OperationExecutionStatus.SUCCEEDED);
        assertThat(calls).hasValue(1);
        assertThat(received).hasValue(expected);
        assertThat(OperationSchemaValidator.violations(registration.resultSchema(), result.result())).isEmpty();
        assertThat(result.result().path("resultType").asText()).isEqualTo("execution_profile_export");
        assertThat(result.result().path("status").asText()).isEqualTo("ok");
        assertThat(result.result().path("reasonCode").asText()).isEqualTo("success");
        assertThat(result.result().path("details").path("exportId").asText()).isEqualTo("fixture-export");
        assertThat(result.result().path("details").has("artifactType")).isFalse();
        assertThat(result.result().path("details").has("action")).isFalse();
        assertThat(result.result().path("details").path("authorization").asText())
                .isEqualTo("***REDACTED***");
        assertThat(registration.provenance().resultComparison())
                .isEqualTo("export_result_fields_preserved_without_artifact_discriminators");
        assertThat(registration.provenance().parityScenario())
                .isEqualTo("execution_profile_export_input_contract");
    }
}
