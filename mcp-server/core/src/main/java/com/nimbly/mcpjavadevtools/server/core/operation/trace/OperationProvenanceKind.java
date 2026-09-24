package com.nimbly.mcpjavadevtools.server.core.operation.trace;

/** Distinguishes released invocation mappings from intentionally new direct CDE operations. */
public enum OperationProvenanceKind {
    RELEASED_LEGACY_INVOCATION,
    NEW_DIRECT_CDE_OPERATION
}
