package com.nimbly.mcpjavadevtools.server.core.feature.suite.security.operation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.security.SecuritySuiteFeature;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.TrustedSuiteExecution;
import java.util.List;

/** Binds Security Suite CDE operations to a trusted persisted-plan boundary. */
public class TrustedSecuritySuiteRegistrations {
    private TrustedSecuritySuiteRegistrations() {
    }

    public static List<OperationRegistration<?, ?>> create(
            SecuritySuiteFeature feature, ObjectMapper mapper, TrustedSuiteExecution trusted) {
        return SecuritySuiteOperationRegistrations.createTrusted(feature, mapper, trusted);
    }
}
