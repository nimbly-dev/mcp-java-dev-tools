package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result;

/**
 * Closed Core processing stages safe to expose in deterministic failure metadata.
 */
public enum ProbeFailureStep {

    INPUT_VALIDATION("input_validation"),
    PROBE_REGISTRY_RESOLUTION("probe_registry_resolution"),
    PROBE_TARGET_VALIDATION("probe_target_validation"),
    PROBE_DIAGNOSTICS("probe_diagnostics"),
    PROBE_STATUS("probe_status"),
    PROBE_ENDPOINT_RESPONSE("probe_endpoint_response");

    private final String value;

    ProbeFailureStep(String value) {
        this.value = value;
    }

    /**
     * Returns the stable diagnostic step value.
     *
     * @return stable diagnostic step value
     */
    public String value() {
        return value;
    }
}
