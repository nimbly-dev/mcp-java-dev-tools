package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.binding.OperationRegistration;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationCatalogPageBuilder;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationDirectoryException;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationExecutionStatus;
import com.nimbly.mcpjavadevtools.server.core.operation.execution.OperationInvocationExecution;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifest;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestAssembler;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationManifestDocument;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceEntry;

/** Aggregate Core Catalog-Describe-Execute directory over explicit registrations. */
public class OperationDirectory {

    private final OperationManifest manifest;
    private final ObjectMapper mapper;

    /** Creates a directory with the Core-owned JSON mapper. */
    OperationDirectory(OperationManifest manifest) {
        this(manifest, new ObjectMapper());
    }

    /** Creates a directory from exact Java registrations and XML documentation. */
    public OperationDirectory(
            List<? extends OperationRegistration<?, ?>> registrations,
            OperationManifestDocument document) {
        this(OperationManifestAssembler.assemble(registrations, document));
    }

    /** Creates a directory from exact registrations with the supplied JSON mapper. */
    public OperationDirectory(
            List<? extends OperationRegistration<?, ?>> registrations,
            OperationManifestDocument document,
            ObjectMapper mapper) {
        this(OperationManifestAssembler.assemble(registrations, document), mapper);
    }

    /** Creates the production aggregate with strict migration validation. */
    public static OperationDirectory strict(
            List<? extends OperationRegistration<?, ?>> registrations,
            OperationManifestDocument document,
            ObjectMapper mapper) {
        return new OperationDirectory(
                OperationManifestAssembler.assembleStrict(registrations, document), mapper);
    }

    /** Creates a directory with an explicitly supplied transport-neutral mapper. */
    OperationDirectory(OperationManifest manifest, ObjectMapper mapper) {
        this.manifest = Objects.requireNonNull(manifest, "manifest must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /** Returns a bounded deterministic page of compact catalog rows. */
    public CatalogPage catalog(CatalogQuery query) {
        return OperationCatalogPageBuilder.build(manifest, query);
    }

    /** Describes exactly one registered operation without executing it. */
    public OperationDescriptor describe(OperationId operationId) {
        OperationRegistration<?, ?> registration = manifest.registration(operationId);
        if (registration == null) {
            throw new OperationDirectoryException(
                    OperationExecutionStatus.UNSUPPORTED_OPERATION,
                    "unsupported_operation",
                    "The operation ID is not registered.");
        }
        return registration.descriptor();
    }

    /** Executes one exact invocation without requiring a preceding describe call. */
    public OperationExecutionResult execute(OperationInvocation invocation) {
        if (invocation == null) {
            return OperationInvocationExecution.run(manifest, mapper, invocation);
        }
        return OperationInvocationExecutionBridge.run(manifest, mapper, invocation);
    }

    /** Convenience exact-ID execution entry point. */
    public OperationExecutionResult execute(OperationId operationId, JsonNode input) {
        return OperationInvocationExecution.run(manifest, mapper, operationId, input, false);
    }

    /** @return the immutable assembled manifest */
    public OperationManifest manifest() {
        return manifest;
    }

    /** @return generated aggregate trace inventory */
    public List<OperationTraceEntry> traceInventory() {
        return manifest.traceInventory();
    }
}
