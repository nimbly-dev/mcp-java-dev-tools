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
            entries.add(new OperationTraceEntry(
                    exposure.toolName(),
                    descriptor.action(),
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
                    trace.collaboratorRoles()));
        }
        return List.copyOf(entries);
    }
}
