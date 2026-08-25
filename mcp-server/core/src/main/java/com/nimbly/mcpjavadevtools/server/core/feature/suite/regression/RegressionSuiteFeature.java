package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.request.RegressionSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;

/** Spring-independent public entry point for Regression Suite behavior. */
public interface RegressionSuiteFeature {

    /** Executes one Regression Suite action. */
    RegressionSuiteResult execute(RegressionSuiteRequest request);
}
