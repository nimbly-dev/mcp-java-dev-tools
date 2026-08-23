package com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport;

import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.request.ExecutionProfileExportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.executionprofileexport.model.result.ExecutionProfileExportResult;

/** Spring-independent public entry point for Execution Profile Export. */
public interface ExecutionProfileExportFeature {

    /** Executes one typed export operation. */
    ExecutionProfileExportResult execute(ExecutionProfileExportRequest request);
}
