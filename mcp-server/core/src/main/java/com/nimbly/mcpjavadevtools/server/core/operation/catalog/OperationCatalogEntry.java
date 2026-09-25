package com.nimbly.mcpjavadevtools.server.core.operation.catalog;

import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

import java.util.List;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;

/** Compact deterministic row returned by aggregate catalog search. */
public record OperationCatalogEntry(
        OperationId operationId,
        String api,
        String operation,
        String classification,
        String summary,
        List<String> tags) {

    /** Validates and copies one compact catalog row. */
    public OperationCatalogEntry {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(api, "api must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(tags, "tags must not be null");
        tags = List.copyOf(tags);
    }

    /** Creates a compact result from one complete descriptor. */
    public static OperationCatalogEntry from(OperationDescriptor descriptor) {
        return new OperationCatalogEntry(
                descriptor.operationId(),
                descriptor.api(),
                descriptor.operation(),
                descriptor.classification(),
                descriptor.summary(),
                descriptor.documentation().tags());
    }
}
