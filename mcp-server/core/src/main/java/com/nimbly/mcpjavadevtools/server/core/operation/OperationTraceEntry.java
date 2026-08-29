package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One deterministic Tool-to-Operation inventory row. */
public record OperationTraceEntry(
        String toolName,
        String action,
        String requestType,
        String resultType,
        String mcpAdapter,
        String requestMapper,
        String coreFeature,
        String operationCatalog,
        String descriptorType,
        String executableOwner,
        String responseMapper,
        String focusedEvidence,
        String sideEffect,
        Map<String, String> collaboratorRoles) {

    /** Keeps generated inventory rows immutable and stable. */
    public OperationTraceEntry {
        Objects.requireNonNull(collaboratorRoles, "collaboratorRoles must not be null");
        collaboratorRoles = Collections.unmodifiableMap(new LinkedHashMap<>(collaboratorRoles));
    }
}
