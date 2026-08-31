package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationLegacyIdentity;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** One catalog binding to a concrete final Artifact-family owner instance. */
final class ArtifactOperation implements Operation<
        ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> {

    private final ArtifactManagementAction operationId;
    private final Class<?> ownerType;
    private final String ownerMethod;
    private final Function<ArtifactManagementRequest, ArtifactManagementResult> executor;
    private final OperationDescriptor descriptor;

    ArtifactOperation(
            ArtifactManagementAction operationId,
            Class<?> declaredOwnerType,
            Object owner,
            String ownerMethod,
            Function<ArtifactManagementRequest, ArtifactManagementResult> executor,
            OperationTraceMetadata baseTrace) {
        this.operationId = Objects.requireNonNull(operationId, "operationId must not be null");
        this.ownerType = Objects.requireNonNull(owner, "owner must not be null").getClass();
        Class<?> checkedDeclaredOwnerType = Objects.requireNonNull(
                declaredOwnerType, "declared owner type must not be null");
        if (ownerMethod == null || ownerMethod.isBlank()) {
            throw new IllegalArgumentException("owner method must not be blank");
        }
        this.ownerMethod = ownerMethod;
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        OperationTraceMetadata trace = trace(baseTrace);
        this.descriptor = new OperationDescriptor(
                ArtifactOperationCatalog.TOOL_NAME,
                operationId.routeId(),
                ArtifactManagementRequest.class.getName(),
                ArtifactManagementResult.class.getName(),
                checkedDeclaredOwnerType.getName() + "#" + ownerMethod,
                trace);
    }

    @Override
    public ArtifactManagementAction operationId() {
        return operationId;
    }

    @Override
    public OperationDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String executableOwner() {
        return ownerType.getName() + "#" + ownerMethod;
    }

    @Override
    public OperationLegacyIdentity legacyIdentity() {
        return new OperationLegacyIdentity(
                ArtifactOperationCatalog.TOOL_NAME,
                operationId.routeId(),
                false,
                Map.of("artifactType", operationId.artifactType().value(),
                        "action", operationId.action().value()),
                "artifact_type_and_action_to_canonical_operation_id",
                "artifact_result_envelope_fields_and_details_preserved",
                "artifact_management_" + operationId.artifactType().value()
                        + "_" + operationId.action().value());
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest input) {
        return executor.apply(input);
    }

    private OperationTraceMetadata trace(OperationTraceMetadata baseTrace) {
        Objects.requireNonNull(baseTrace, "trace must not be null");
        Map<String, String> roles = new LinkedHashMap<>(baseTrace.collaboratorRoles());
        roles.put("familyOperationOwner", ownerType.getName());
        roles.put("familyOperationMethod", ownerMethod);
        return new OperationTraceMetadata(
                baseTrace.mcpAdapter(),
                baseTrace.requestMapper(),
                baseTrace.coreFeature(),
                baseTrace.responseMapper(),
                baseTrace.focusedEvidence(),
                ArtifactOperationEffects.sideEffect(operationId),
                roles);
    }
}
