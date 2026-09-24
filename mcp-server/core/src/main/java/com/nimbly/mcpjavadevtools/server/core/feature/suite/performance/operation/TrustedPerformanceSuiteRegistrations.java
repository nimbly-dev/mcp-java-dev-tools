package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.PerformanceSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import java.util.List;

/** Binds Performance Suite CDE operations to a trusted persisted-plan boundary. */
public class TrustedPerformanceSuiteRegistrations {
    private TrustedPerformanceSuiteRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            PerformanceSuiteFeature feature, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        return PerformanceSuiteOperationRegistrations.createTrusted(feature, mapper, trusted);
    }
}
