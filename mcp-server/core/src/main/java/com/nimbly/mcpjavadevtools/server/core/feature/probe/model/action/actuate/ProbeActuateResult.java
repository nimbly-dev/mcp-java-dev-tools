package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeActionResult;

/**
 * Safe result of a session-scoped actuation operation.
 *
 * @param actuated whether the Sidecar confirmed the command
 * @param command effective Sidecar command
 * @param mode effective runtime mode
 * @param sessionId effective session identity
 * @param actuatorId effective actuator identity
 * @param targetKey effective Strict Line Key
 * @param returnBoolean effective branch decision
 * @param ttlMs effective TTL
 * @param expiresAtEpoch expiry timestamp
 * @param scopeState Sidecar session scope state
 * @param reason bounded Sidecar-safe failure reason
 */
public record ProbeActuateResult(
        boolean actuated,
        String command,
        String mode,
        String sessionId,
        String actuatorId,
        String targetKey,
        Boolean returnBoolean,
        Long ttlMs,
        Long expiresAtEpoch,
        String scopeState,
        String reason) implements ProbeActionResult {
}
