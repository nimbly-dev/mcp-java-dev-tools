package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationProvenance;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Legacy Java Tool routing retained until MCPJVM-611 removes ArtifactManagementMcpTool's
 * dependency on DefaultArtifactManagementFeature and this capability catalog.
 * COMPATIBILITY_RETAINED_UNTIL_611.
 */
final class ArtifactOperation implements Operation<
        ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> {

    private final ArtifactManagementAction operationId;
    private final String executableOwner;
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
        Class<?> ownerType = Objects.requireNonNull(owner, "owner must not be null").getClass();
        Class<?> checkedDeclaredOwnerType = Objects.requireNonNull(
                declaredOwnerType, "declared owner type must not be null");
        if (ownerMethod == null || ownerMethod.isBlank()) {
            throw new IllegalArgumentException("owner method must not be blank");
        }
        this.executableOwner = ownerType.getName() + "#" + ownerMethod;
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        OperationTraceMetadata trace = trace(baseTrace, ownerType, ownerMethod);
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
        return executableOwner;
    }

    @Override
    public OperationProvenance provenance() {
        return ArtifactOperationRegistrations.identity(operationId);
    }

    @Override
    public ArtifactManagementResult execute(ArtifactManagementRequest input) {
        return executor.apply(input);
    }

    private OperationTraceMetadata trace(
            OperationTraceMetadata baseTrace, Class<?> ownerType, String ownerMethod) {
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
