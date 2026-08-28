package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.request.PerformanceSuiteRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.result.PerformanceSuiteResult;

/** Spring-independent public entry point for Performance Suite behavior. */
public interface PerformanceSuiteFeature {

    /** Executes one Performance Suite action. */
    PerformanceSuiteResult execute(PerformanceSuiteRequest request);
}
