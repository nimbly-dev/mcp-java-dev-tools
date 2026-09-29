package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.model.result.RegressionSuiteResult;

/** Contract consumed by direct CDE execution and cross-suite orchestration. */
public interface RegressionSuiteFeature {

    /** Validates a resolved Regression plan before external work. */
    RegressionSuiteResult preflight(JsonNode input);

    /** Executes a resolved Regression plan after preflight. */
    RegressionSuiteResult executePlan(JsonNode input);
}
