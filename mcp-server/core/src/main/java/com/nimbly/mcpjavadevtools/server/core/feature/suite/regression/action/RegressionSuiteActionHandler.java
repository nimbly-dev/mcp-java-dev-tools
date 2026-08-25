package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;

/** Typed Regression Suite action contract. */
public interface RegressionSuiteActionHandler extends ActionHandler<
        RegressionSuiteAction, RegressionSuiteRequest, RegressionSuiteResult> {
}
