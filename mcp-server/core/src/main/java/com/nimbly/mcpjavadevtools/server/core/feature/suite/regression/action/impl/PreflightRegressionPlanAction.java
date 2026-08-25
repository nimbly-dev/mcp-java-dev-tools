package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.impl;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.RegressionSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.preflight.RegressionPlanPreflight;

/** Concrete action that validates a Regression plan before runtime execution. */
public final class PreflightRegressionPlanAction implements RegressionSuiteActionHandler {

    private final RegressionPlanPreflight preflight;

    /** Creates the action with the purpose-owned preflight policy. */
    public PreflightRegressionPlanAction(RegressionPlanPreflight preflight) {
        this.preflight = preflight;
    }

    @Override
    public RegressionSuiteAction action() {
        return RegressionSuiteAction.PREFLIGHT;
    }

    @Override
    public RegressionSuiteResult execute(RegressionSuiteRequest request) {
        return preflight.validate(request.input());
    }
}
