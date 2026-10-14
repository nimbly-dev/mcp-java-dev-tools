package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.analysis;

/** Sample counts and estimated aggregate inclusive thread time, independent of the workload window. */
public record PerformanceStackSamples(int count, double timeMs) {
    public PerformanceStackSamples {
        if (count < 0 || !Double.isFinite(timeMs) || timeMs < 0) {
            throw new IllegalArgumentException("sample weights must be finite and non-negative");
        }
    }

    public PerformanceStackSamples plus(PerformanceStackSamples other) {
        return new PerformanceStackSamples(Math.addExact(count, other.count), timeMs + other.timeMs);
    }
}
