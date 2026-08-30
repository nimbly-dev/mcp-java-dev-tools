package com.nimbly.mcpjavadevtools.server.core.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Generates stable Tool-to-Operation inventory rows from a live catalog. */
public class OperationTraceInventory {

    private OperationTraceInventory() {
    }

    /**
     * Generates inventory from registered operations rather than a duplicated
     * list or runtime classpath scan.
     *
     * @param catalog complete capability catalog
     * @param <A> closed operation identifier
     * @param <I> typed operation input
     * @param <O> typed operation output
     * @return operation rows in closed-enum declaration order
     */
    public static <A extends Enum<A>, I, O> List<OperationTraceEntry> generate(
            OperationCatalog<A, I, O> catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        OperationExposure exposure = catalog.exposure();
        List<OperationTraceEntry> entries = new ArrayList<>();
        for (Operation<A, I, O> operation : catalog.registeredOperations()) {
            OperationDescriptor descriptor = operation.descriptor();
            OperationTraceMetadata trace = descriptor.trace();
            OperationLegacyIdentity identity = operation.legacyIdentity();
            entries.add(new OperationTraceEntry(
                    exposure.toolName(),
                    identity.action(),
                    identity.actionless(),
                    descriptor.requestType(),
                    descriptor.resultType(),
                    exposure.adapterType(),
                    trace.requestMapper(),
                    trace.coreFeature(),
                    catalog.operationCatalog(),
                    OperationDescriptor.class.getName(),
                    descriptor.executableOwner(),
                    trace.responseMapper(),
                    trace.focusedEvidence(),
                    trace.sideEffect(),
                    trace.collaboratorRoles(),
                    identity.inventory(descriptor),
                    descriptor.operationId().value()));
        }
        return List.copyOf(entries);
    }

    /** Generates aggregate rows from an assembled immutable manifest. */
    public static List<OperationTraceEntry> generate(OperationManifest manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        List<OperationTraceEntry> entries = new ArrayList<>();
        for (OperationRegistration<?, ?> registration
                : manifest.registrations()) {
            OperationDescriptor descriptor = registration.descriptor();
            OperationTraceMetadata trace = descriptor.trace();
            OperationLegacyIdentity identity = registration.legacyIdentity();
            entries.add(new OperationTraceEntry(
                    identity.toolName(),
                    identity.action(),
                    identity.actionless(),
                    descriptor.requestType(),
                    descriptor.resultType(),
                    trace.mcpAdapter(),
                    trace.requestMapper(),
                    trace.coreFeature(),
                    registration.operationCatalog(),
                    OperationDescriptor.class.getName(),
                    descriptor.executableOwner(),
                    trace.responseMapper(),
                    trace.focusedEvidence(),
                    trace.sideEffect(),
                    trace.collaboratorRoles(),
                    identity.inventory(descriptor),
                    descriptor.operationId().value()));
        }
        return List.copyOf(entries);
    }
}
