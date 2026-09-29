package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;

/** Contract consumed by direct CDE execution and cross-suite orchestration. */
public interface PerformanceSuiteFeature {

    /** Executes a resolved Performance plan with required Probe verification. */
    PerformanceSuiteResult executePlan(JsonNode input);
}
