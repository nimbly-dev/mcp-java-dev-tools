package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.RegressionSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.execution.RegressionPlanExecutor;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;

/** Concrete action that executes one preflight-valid Regression plan. */
public final class ExecuteRegressionPlanAction implements RegressionSuiteActionHandler {

    private final RegressionPlanExecutor executor;

    /** Creates the action with its purpose-owned execution coordinator. */
    public ExecuteRegressionPlanAction(RegressionPlanExecutor executor) {
        this.executor = executor;
    }

    @Override
    public RegressionSuiteAction action() {
        return RegressionSuiteAction.EXECUTE_PLAN;
    }

    @Override
    public RegressionSuiteResult execute(RegressionSuiteRequest request) {
        return executor.execute(request.input());
    }
}
