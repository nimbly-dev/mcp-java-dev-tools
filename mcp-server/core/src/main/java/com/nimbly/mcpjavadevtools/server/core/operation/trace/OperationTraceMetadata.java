package com.nimbly.mcpjavadevtools.server.core.operation.trace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable Application-to-Core trace ownership metadata. */
public record OperationTraceMetadata(
        String mcpAdapter,
        String requestMapper,
        String coreFeature,
        String responseMapper,
        String focusedEvidence,
        String sideEffect,
        Map<String, String> collaboratorRoles) {

    /** Validates and deterministically copies trace ownership values. */
    public OperationTraceMetadata {
        Objects.requireNonNull(mcpAdapter, "mcpAdapter must not be null");
        Objects.requireNonNull(requestMapper, "requestMapper must not be null");
        Objects.requireNonNull(coreFeature, "coreFeature must not be null");
        Objects.requireNonNull(responseMapper, "responseMapper must not be null");
        Objects.requireNonNull(focusedEvidence, "focusedEvidence must not be null");
        Objects.requireNonNull(sideEffect, "sideEffect must not be null");
        if (mcpAdapter.isBlank() || requestMapper.isBlank() || coreFeature.isBlank()
                || responseMapper.isBlank() || focusedEvidence.isBlank() || sideEffect.isBlank()) {
            throw new IllegalArgumentException("operation trace values must not be blank");
        }
        Objects.requireNonNull(collaboratorRoles, "collaboratorRoles must not be null");
        if (collaboratorRoles.isEmpty()) {
            throw new IllegalArgumentException("collaboratorRoles must not be empty");
        }
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : collaboratorRoles.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()
                    || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("collaborator roles must not be blank");
            }
            sorted.put(entry.getKey().trim(), entry.getValue().trim());
        }
        collaboratorRoles = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
