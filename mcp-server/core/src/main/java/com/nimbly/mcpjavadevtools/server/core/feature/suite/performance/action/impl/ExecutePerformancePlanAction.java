package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.action.PerformanceSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.execution.PerformancePlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.action.PerformanceSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;
import java.util.Objects;

/** Executes one validated JMeter-backed Performance plan. */
public final class ExecutePerformancePlanAction implements PerformanceSuiteActionHandler {

    private final PerformancePlanExecutor executor;

    /** Creates the action with its purpose-owned executor. */
    public ExecutePerformancePlanAction(PerformancePlanExecutor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public PerformanceSuiteAction action() {
        return PerformanceSuiteAction.EXECUTE_PLAN;
    }

    @Override
    public PerformanceSuiteResult execute(PerformanceSuiteRequest request) {
        return executor.execute(request.input());
    }
}
