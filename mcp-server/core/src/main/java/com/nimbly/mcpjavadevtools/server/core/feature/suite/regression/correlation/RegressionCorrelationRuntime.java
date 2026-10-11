package com.nimbly.mcpjavadevtools.server.core.feature.suite.regression.correlation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/** Runtime boundary used by Regression correlation to collect Sidecar evidence. */
public interface RegressionCorrelationRuntime {

    /** Configures bounded runtime evidence collection before trigger execution. */
    Session open(JsonNode correlation, String executionId);

    /** Active correlation lease and event cursor. */
    interface Session extends AutoCloseable {

        /** Reads newly observed runtime events after the session baseline. */
        List<Map<String, Object>> readEvents();

        /** Releases all correlation leases owned by this execution. */
        @Override
        void close();
    }

    /** Returns a fail-closed runtime for Core-only construction without Sidecar wiring. */
    static RegressionCorrelationRuntime unavailable() {
        return (correlation, executionId) -> {
            throw new RegressionCorrelationRuntimeException("correlation_runtime_unavailable");
        };
    }
}
