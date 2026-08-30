package com.nimbly.mcpjavadevtools.server.core.operation.trace;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

/** One deterministic Tool-to-Operation inventory row. */
public record OperationTraceEntry(
        String toolName,
        String action,
        boolean actionless,
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
        Map<String, String> collaboratorRoles,
        Map<String, String> compatibility,
        String operationId) {

    /** Preserves the original inventory row constructor. */
    public OperationTraceEntry(
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
        this(toolName, action, false, requestType, resultType, mcpAdapter, requestMapper, coreFeature,
                operationCatalog, descriptorType, executableOwner, responseMapper, focusedEvidence,
                sideEffect, collaboratorRoles, Map.of(), OperationId.fromLegacy(toolName, action).value());
    }

    /** Preserves the first aggregate inventory shape without compatibility details. */
    public OperationTraceEntry(
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
            Map<String, String> collaboratorRoles,
            String operationId) {
        this(toolName, action, false, requestType, resultType, mcpAdapter, requestMapper, coreFeature,
                operationCatalog, descriptorType, executableOwner, responseMapper, focusedEvidence,
                sideEffect, collaboratorRoles, Map.of(), operationId);
    }

    /** Keeps generated inventory rows immutable and stable. */
    public OperationTraceEntry {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(action, "action must not be null");
        if ((actionless && !action.isBlank()) || (!actionless && action.isBlank())) {
            throw new IllegalArgumentException("trace actionless identity is inconsistent");
        }
        Objects.requireNonNull(collaboratorRoles, "collaboratorRoles must not be null");
        Objects.requireNonNull(compatibility, "compatibility must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        collaboratorRoles = Collections.unmodifiableMap(new TreeMap<>(collaboratorRoles));
        compatibility = Collections.unmodifiableMap(new TreeMap<>(compatibility));
    }
}
