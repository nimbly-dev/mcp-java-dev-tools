package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.export.ExecutionExportOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.plan.PlanOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.project.ProjectContextOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.probeconfig.ProbeConfigOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact.run.RunResultOperations;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action.ArtifactManagementAction;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.request.ArtifactManagementRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.result.ArtifactManagementResult;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete Catalog-Describe-Execute surface for Artifact Management. */
public final class ArtifactOperationCatalog {

    /** Stable MCP Tool name owned by this catalog. */
    public static final String TOOL_NAME = "artifact_management";

    private final OperationCatalog<
            ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult> catalog;

    /** Creates a complete catalog with composition-supplied trace metadata. */
    public ArtifactOperationCatalog(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            PlanOperations plans,
            RunResultOperations runs,
            ExecutionExportOperations exports,
            OperationExposure exposure,
            OperationTraceMetadata trace) {
        catalog = new OperationCatalog<>(
                ArtifactManagementAction.class,
                Objects.requireNonNull(exposure, "exposure must not be null"),
                ArtifactOperationCatalog.class,
                registrations(probe, project, plans, runs, exports,
                        Objects.requireNonNull(trace, "trace must not be null")));
    }

    /** Creates a catalog with deterministic local trace metadata for Core tests. */
    public ArtifactOperationCatalog(
            ProbeConfigOperations probe,
            ProjectContextOperations project,
            PlanOperations plans,
            RunResultOperations runs,
            ExecutionExportOperations exports,
            OperationExposure exposure) {
        this(probe, project, plans, runs, exports, exposure, defaultTrace(exposure));
    }

    /** @return immutable descriptors in the closed family/action order */
    public List<OperationDescriptor> catalog() {
        return catalog.catalog();
    }

    /** @return immutable typed operations for aggregate manifest composition */
    public List<Operation<
            ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult>> operations() {
        return catalog.operations();
    }

    /** @param action selected family/action pair @return its descriptor */
    public OperationDescriptor describe(ArtifactManagementAction action) {
        return catalog.describe(action);
    }

    /** Executes exactly the registered owner for one family/action pair. */
    public ArtifactManagementResult execute(
            ArtifactManagementAction action, ArtifactManagementRequest request) {
        return catalog.execute(action, request);
    }

    /** @return the generated complete Tool-to-Operation inventory */
    public List<OperationTraceEntry> traceInventory() {
        return catalog.traceInventory();
    }

    private static List<? extends Operation<
            ArtifactManagementAction, ArtifactManagementRequest, ArtifactManagementResult>> registrations(
                    ProbeConfigOperations probe,
                    ProjectContextOperations project,
                    PlanOperations plans,
                    RunResultOperations runs,
                    ExecutionExportOperations exports,
                    OperationTraceMetadata trace) {
        return ArtifactOperationRegistrations.legacyOperations(
                Objects.requireNonNull(probe, "probe operations must not be null"),
                Objects.requireNonNull(project, "project operations must not be null"),
                Objects.requireNonNull(plans, "plan operations must not be null"),
                Objects.requireNonNull(runs, "run operations must not be null"),
                Objects.requireNonNull(exports, "export operations must not be null"), trace);
    }

    static OperationTraceMetadata defaultTrace(OperationExposure exposure) {
        return new OperationTraceMetadata(
                exposure.adapterType(),
                ArtifactOperationCatalog.class.getName(),
                "com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.ArtifactManagementFeature",
                ArtifactOperationCatalog.class.getName(),
                ArtifactOperationCatalog.class.getName(),
                "artifact_management",
                Map.of("artifactSupport", "ArtifactManagementSupport"));
    }
}
