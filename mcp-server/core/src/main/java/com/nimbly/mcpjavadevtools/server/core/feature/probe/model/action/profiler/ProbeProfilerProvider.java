package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.profiler;

/**
 * Closed provider intent allowlist supported by the Sidecar profiler.
 */
public enum ProbeProfilerProvider {

    AUTO("auto"),
    ASYNC_PROFILER("async-profiler"),
    JFR("jfr");

    private final String value;

    ProbeProfilerProvider(String value) {
        this.value = value;
    }

    /**
     * Returns the Sidecar provider value.
     *
     * @return stable provider value
     */
    public String value() {
        return value;
    }
}
