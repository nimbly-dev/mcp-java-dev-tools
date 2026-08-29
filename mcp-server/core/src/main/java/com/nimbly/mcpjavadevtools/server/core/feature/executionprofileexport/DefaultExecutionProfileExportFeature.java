package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport;

import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.operation.ExecutionProfileExportOperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import java.util.Objects;

/** Complete Catalog-Describe-Execute Execution Profile Export Feature. */
public final class DefaultExecutionProfileExportFeature implements ExecutionProfileExportFeature {

    private final ExecutionProfileExportOperationCatalog operationCatalog;

    /** Creates the feature from its complete capability-owned operation catalog. */
    public DefaultExecutionProfileExportFeature(ExecutionProfileExportOperationCatalog operationCatalog) {
        this.operationCatalog = Objects.requireNonNull(operationCatalog, "operationCatalog must not be null");
    }

    @Override
    public ExecutionProfileExportResult execute(ExecutionProfileExportRequest request) {
        if (request == null || request.action() == null) {
            return ExecutionProfileExportResult.invalidRequest();
        }
        return operationCatalog.execute(request.action(), request);
    }
}
