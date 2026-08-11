package com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.actuate;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.target.ProbeTargetSelector;
import java.time.Duration;

/**
 * Typed session-scoped Probe actuation request.
 *
 * @param targetSelector direct or registered Probe target selector
 * @param command requested arm or disarm operation
 * @param sessionId required session identity
 * @param actuatorId optional audit identity
 * @param targetKey required Strict Line Key for arm
 * @param returnBoolean required branch decision for arm
 * @param ttlMs required positive TTL for arm
 * @param timeout optional bounded endpoint timeout
 */
public record ProbeActuateRequest(
        ProbeTargetSelector targetSelector,
        ProbeActuateCommand command,
        String sessionId,
        String actuatorId,
        String targetKey,
        Boolean returnBoolean,
        Long ttlMs,
        Duration timeout) implements ProbeRequest {

    /**
     * Normalizes boundary strings before action-specific validation.
     */
    public ProbeActuateRequest {
        sessionId = normalize(sessionId);
        actuatorId = normalize(actuatorId);
        targetKey = normalize(targetKey);
    }

    @Override
    public ProbeAction action() {
        return ProbeAction.ACTUATE;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
