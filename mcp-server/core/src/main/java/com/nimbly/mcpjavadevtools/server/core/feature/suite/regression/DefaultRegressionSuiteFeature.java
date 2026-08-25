package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.action.RegressionSuiteActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.action.RegressionSuiteAction;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;
import java.util.List;
import java.util.Map;

/** Default production Regression Suite Feature implementation. */
public final class DefaultRegressionSuiteFeature implements RegressionSuiteFeature {

    private final EnumActionDispatcher<RegressionSuiteAction, RegressionSuiteRequest, RegressionSuiteResult> dispatcher;

    /** Creates the Feature with a complete set of action handlers. */
    public DefaultRegressionSuiteFeature(List<? extends RegressionSuiteActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(RegressionSuiteAction.class, handlers);
    }

    @Override
    public RegressionSuiteResult execute(RegressionSuiteRequest request) {
        if (request == null || request.action() == null) {
            return RegressionSuiteResult.blocked(
                    "regression_suite_request_invalid",
                    "a Regression Suite action is required",
                    Map.of("failedStep", "input_validation"));
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
