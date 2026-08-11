package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response;

/**
 * Safe application-port hint without untrusted confidence telemetry.
 *
 * @param value hinted application port
 * @param source controlled Sidecar source label
 */
public record ProbeRuntimePortHint(Integer value, String source) {
}
