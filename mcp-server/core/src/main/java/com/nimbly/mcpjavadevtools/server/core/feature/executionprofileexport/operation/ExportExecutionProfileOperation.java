package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportArtifactGateway;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactType;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.Map;
import java.util.Objects;

/** Concrete execution owner for the released execution-profile export action. */
public final class ExportExecutionProfileOperation implements Operation<
        ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult> {

    private final ExecutionExportArtifactGateway artifactGateway;
    private final ExecutionProfileExportArtifactInputMapper inputMapper;
    private final OperationDescriptor descriptor;

    /** Creates the operation with explicit trace metadata supplied by composition. */
    public ExportExecutionProfileOperation(
            ExecutionExportArtifactGateway artifactGateway,
            ExecutionProfileExportArtifactInputMapper inputMapper,
            OperationTraceMetadata trace) {
        this.artifactGateway = Objects.requireNonNull(artifactGateway, "artifactGateway must not be null");
        this.inputMapper = Objects.requireNonNull(inputMapper, "inputMapper must not be null");
        this.descriptor = new OperationDescriptor(
                ExecutionProfileExportOperationCatalog.TOOL_NAME,
                ExecutionProfileExportOperationCatalog.ACTION,
                ExecutionProfileExportRequest.class.getName(),
                ExecutionProfileExportResult.class.getName(),
                getClass().getName(),
                Objects.requireNonNull(trace, "trace must not be null"));
    }

    @Override
    public ExecutionProfileExportAction operationId() {
        return ExecutionProfileExportAction.EXPORT;
    }

    @Override
    public OperationDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String executableOwner() {
        return getClass().getName();
    }

    @Override
    public OperationLegacyIdentity legacyIdentity() {
        return new OperationLegacyIdentity(
                ExecutionProfileExportOperationCatalog.TOOL_NAME,
                "",
                true,
                Map.of(),
                "execution_profile_export_input_to_typed_artifact_request",
                "export_result_fields_preserved_without_artifact_discriminators",
                "execution_profile_export_input_contract");
    }

    @Override
    public ExecutionProfileExportResult execute(ExecutionProfileExportRequest request) {
        ArtifactManagementResult result = artifactGateway.generate(new ArtifactManagementRequest(
                ArtifactType.EXECUTION_EXPORT,
                ArtifactAction.GENERATE,
                inputMapper.map(request)));
        return ExecutionProfileExportResult.fromArtifactResult(result);
    }
}
