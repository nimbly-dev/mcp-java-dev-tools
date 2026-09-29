package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.RegressionSuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import java.util.List;

/** Exposes trusted persisted-plan binding without widening the public registration API. */
public class TrustedRegressionSuiteRegistrations {
    private TrustedRegressionSuiteRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            RegressionSuiteFeature feature, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        return RegressionSuiteOperationRegistrations.createTrusted(feature, mapper, trusted);
    }
}
