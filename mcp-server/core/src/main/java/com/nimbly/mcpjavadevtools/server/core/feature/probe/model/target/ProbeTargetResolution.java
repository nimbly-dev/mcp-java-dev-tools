package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target;

/**
 * Deterministic target-resolution outcome without a runtime endpoint call.
 */
public sealed interface ProbeTargetResolution permits ResolvedProbeTarget, UnresolvedProbeTarget {
}
