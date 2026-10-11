package com.nimbly.mcpjavadevtools.server.core.operation.trace;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** One deterministic Tool-to-Operation inventory row. */
public record OperationTraceEntry(
        String toolName,
        String action,
        String requestType,
        String resultType,
        String mcpAdapter,
        String requestMapper,
        String coreFeature,
        String descriptorType,
        String executableOwner,
        String responseMapper,
        String focusedEvidence,
        String sideEffect,
        Map<String, String> collaboratorRoles,
        String operationId) {

    /** Keeps generated inventory rows immutable and stable. */
    public OperationTraceEntry {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(collaboratorRoles, "collaboratorRoles must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        collaboratorRoles = Collections.unmodifiableMap(new TreeMap<>(collaboratorRoles));
    }
}
