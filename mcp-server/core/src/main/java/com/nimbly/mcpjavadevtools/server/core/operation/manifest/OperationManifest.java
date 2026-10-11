package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceInventory;

/** Immutable executable operation set produced by exact manifest assembly. */
public final class OperationManifest {

    private final int version;
    private final Map<OperationId, OperationRegistration<?, ?>> operations;

    /** Creates a deterministic manifest from already enriched registrations. */
    OperationManifest(int version, List<? extends OperationRegistration<?, ?>> registrations) {
        if (version < 1 || version > OperationManifestLoader.CURRENT_VERSION) {
            throw new IllegalArgumentException("unsupported operation manifest version: " + version);
        }
        Objects.requireNonNull(registrations, "manifest registrations must not be null");
        TreeMap<OperationId, OperationRegistration<?, ?>> ordered = new TreeMap<>();
        for (OperationRegistration<?, ?> registration
                : registrations) {
            if (registration == null) {
                throw new IllegalArgumentException("manifest registrations must not contain null");
            }
            OperationId id = registration.descriptor().operationId();
            if (ordered.put(id, registration) != null) {
                throw new IllegalArgumentException("duplicate executable operation: " + id.value());
            }
        }
        this.version = version;
        operations = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
    }

    /** @return authoring format version */
    public int version() {
        return version;
    }

    /** @return executable registrations in canonical ID order */
    public List<OperationRegistration<?, ?>> registrations() {
        return List.copyOf(operations.values());
    }

    /** @return descriptors in canonical ID order */
    public List<OperationDescriptor> descriptors() {
        return operations.values().stream().map(OperationRegistration::descriptor).toList();
    }

    /** @return exact registration, or {@code null} when the ID is closed/unknown */
    public OperationRegistration<?, ?> registration(OperationId operationId) {
        return operations.get(operationId);
    }

    /** @return generated trace rows from this immutable executable set */
    public List<OperationTraceEntry> traceInventory() {
        return OperationTraceInventory.generate(this);
    }
}
