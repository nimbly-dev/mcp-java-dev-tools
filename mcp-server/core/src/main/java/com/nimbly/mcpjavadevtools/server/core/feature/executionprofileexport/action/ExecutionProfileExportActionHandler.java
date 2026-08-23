package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.action.ExecutionProfileExportAction;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;

/** Typed action-handler contract for Execution Profile Export. */
public interface ExecutionProfileExportActionHandler extends ActionHandler<
        ExecutionProfileExportAction, ExecutionProfileExportRequest, ExecutionProfileExportResult> {
}
