package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action.ExecutionProfileExportActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;
import java.util.List;

/** Complete dispatcher-backed Execution Profile Export Feature. */
public final class DefaultExecutionProfileExportFeature implements ExecutionProfileExportFeature {

    private final EnumActionDispatcher<
            ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult> dispatcher;

    /** Creates the complete export action dispatcher. */
    public DefaultExecutionProfileExportFeature(List<? extends ExecutionProfileExportActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(ExecutionProfileExportAction.class, handlers);
    }

    @Override
    public ExecutionProfileExportResult execute(ExecutionProfileExportRequest request) {
        if (request == null || request.action() == null) {
            return ExecutionProfileExportResult.invalidRequest();
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
