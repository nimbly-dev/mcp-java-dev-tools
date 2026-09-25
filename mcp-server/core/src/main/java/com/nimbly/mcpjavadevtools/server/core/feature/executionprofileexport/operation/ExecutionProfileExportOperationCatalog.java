package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.ExecutionProfileExportFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry;
import java.util.List;
import java.util.Objects;

/** Capability-owned Catalog-Describe-Execute surface for export. */
public class ExecutionProfileExportOperationCatalog implements ExecutionProfileExportFeature {

    public static final String TOOL_NAME = "execution_profile_export";
    public static final String ACTION = "export";

    private final OperationCatalog<
            ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult> catalog;

    /** Registers the complete closed export action set. */
    public ExecutionProfileExportOperationCatalog(
            ExportExecutionProfileOperation operation, OperationExposure exposure) {
        catalog = new OperationCatalog<>(
                ExecutionProfileExportAction.class,
                Objects.requireNonNull(exposure, "exposure must not be null"),
                ExecutionProfileExportOperationCatalog.class,
                List.of(Objects.requireNonNull(operation, "operation must not be null")));
    }

    /** @return immutable operation descriptors in enum order */
    public List<OperationDescriptor> catalog() {
        return catalog.catalog();
    }

    /** @return immutable typed operations for aggregate manifest composition */
    public List<Operation<
            ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult>> operations() {
        return catalog.operations();
    }

    /** @param action selected action @return immutable action descriptor */
    public OperationDescriptor describe(ExecutionProfileExportAction action) {
        return catalog.describe(action);
    }

    /**
     * Executes the selected action through exact catalog ownership.
     *
     * @param action selected action
     * @param request typed request
     * @return typed result
     */
    public ExecutionProfileExportResult execute(
            ExecutionProfileExportAction action, ExecutionProfileExportRequest request) {
        return catalog.execute(action, request);
    }

    /** Executes the exported action through the same capability catalog. */
    @Override
    public ExecutionProfileExportResult execute(ExecutionProfileExportRequest request) {
        if (request == null || request.action() == null) {
            return ExecutionProfileExportResult.invalidRequest();
        }
        return execute(request.action(), request);
    }
    /** @return generated Tool-to-Operation inventory */
    public List<OperationTraceEntry> traceInventory() {
        return catalog.traceInventory();
    }
}
