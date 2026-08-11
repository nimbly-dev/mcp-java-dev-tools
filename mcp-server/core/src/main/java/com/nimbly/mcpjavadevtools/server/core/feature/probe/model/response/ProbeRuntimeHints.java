package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.response;

/**
 * Safe, compact runtime hints retained from a Sidecar response.
 *
 * @param mode Sidecar mode when present
 * @param sessionId active session identifier when present
 * @param runtimeInstanceId safe Sidecar instance identifier when present
 * @param actuatorId active actuator identifier when present
 * @param actuateTargetKey active actuation Strict Line Key when present
 * @param actuateReturnBoolean active actuation behavior when present
 * @param expiresAtEpoch actuation expiry epoch when present
 * @param scopeState Sidecar scope state when present
 * @param activeSessionCount active session count when present
 * @param appPort safe application-port hint when present
 */
public record ProbeRuntimeHints(
        String mode,
        String sessionId,
        String runtimeInstanceId,
        String actuatorId,
        String actuateTargetKey,
        Boolean actuateReturnBoolean,
        Long expiresAtEpoch,
        String scopeState,
        Integer activeSessionCount,
        ProbeRuntimePortHint appPort) {
}
