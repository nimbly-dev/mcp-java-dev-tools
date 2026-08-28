package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;

/** Typed Performance Suite action-handler contract. */
public interface PerformanceSuiteActionHandler
        extends ActionHandler<PerformanceSuiteAction, PerformanceSuiteRequest, PerformanceSuiteResult> {
}
