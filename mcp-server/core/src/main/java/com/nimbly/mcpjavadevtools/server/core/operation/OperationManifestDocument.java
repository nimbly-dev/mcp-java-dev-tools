package com.nimbly.mcpjavadevtools.server.core.operation;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable parsed, versioned documentation document before executable joining. */
public final class OperationManifestDocument {

    private final int version;
    private final Map<OperationId, OperationDocumentation> operations;

    /** Creates a validated documentation document. */
    public OperationManifestDocument(int version, Map<OperationId, OperationDocumentation> operations) {
        if (version < 1 || version > OperationManifestLoader.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported operation manifest version: " + version);
        }
        Objects.requireNonNull(operations, "manifest operations must not be null");
        TreeMap<OperationId, OperationDocumentation> ordered = new TreeMap<>();
        for (Map.Entry<OperationId, OperationDocumentation> entry : operations.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || ordered.put(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("operation manifest contains duplicate or null entries");
            }
        }
        this.version = version;
        this.operations = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    /** @return authoring format version */
    public int version() {
        return version;
    }

    /** @return immutable deterministic operation documentation map */
    public Map<OperationId, OperationDocumentation> operations() {
        return operations;
    }

    /** @return documentation for one canonical ID, or {@code null} when absent */
    public OperationDocumentation documentation(OperationId operationId) {
        return operations.get(operationId);
    }
}
