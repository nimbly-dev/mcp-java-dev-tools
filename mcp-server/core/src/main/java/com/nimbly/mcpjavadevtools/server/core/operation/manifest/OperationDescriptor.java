package com.nimbly.mcpjavadevtools.server.core.operation.manifest;

import com.nimbly.mcpjavadevtools.server.core.operation.OperationId;

import java.util.Objects;
import com.nimbly.mcpjavadevtools.server.core.operation.safety.OperationSafetyPolicy;
import com.nimbly.mcpjavadevtools.server.core.operation.schema.OperationSchema;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;

/**
 * Immutable identity and trace metadata for one executable MCP operation.
 *
 * <p>The descriptor contains metadata only. It never stores executable
 * lambdas, I/O, Spring types, or capability behavior.</p>
 */
public record OperationDescriptor(
        String toolName,
        String action,
        String requestType,
        String resultType,
        String executableOwner,
        OperationTraceMetadata trace,
        OperationDescriptorMetadata metadata) {

    /** Preserves the original descriptor constructor for migrated catalogs. */
    public OperationDescriptor(
            String toolName,
            String action,
            String requestType,
            String resultType,
            String executableOwner,
            OperationTraceMetadata trace) {
        this(toolName, action, requestType, resultType, executableOwner, trace,
                OperationDescriptorMetadata.legacy(
                        Objects.requireNonNull(toolName, "toolName must not be null"),
                        Objects.requireNonNull(action, "action must not be null"),
                        Objects.requireNonNull(trace, "trace must not be null").sideEffect()));
    }

    /** Validates the complete minimum descriptor contract. */
    public OperationDescriptor {
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(requestType, "requestType must not be null");
        Objects.requireNonNull(resultType, "resultType must not be null");
        Objects.requireNonNull(executableOwner, "executableOwner must not be null");
        Objects.requireNonNull(trace, "trace must not be null");
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (toolName.isBlank() || action.isBlank() || requestType.isBlank()
                || resultType.isBlank() || executableOwner.isBlank()) {
            throw new IllegalArgumentException("operation descriptor values must not be blank");
        }
        if (!metadata.operationId().equals(OperationId.fromLegacy(toolName, action))) {
            throw new IllegalArgumentException("descriptor operation id does not match Tool/action");
        }
    }

    /** @return aggregate operation identifier */
    public OperationId operationId() {
        return metadata.operationId();
    }

    /** @return API segment used by aggregate catalog filtering */
    public String api() {
        return operationId().api();
    }

    /** @return final operation segment used by aggregate catalog results */
    public String operation() {
        return operationId().operation();
    }

    /** @return documented classification */
    public String classification() {
        return metadata.classification();
    }

    /** @return documented short summary */
    public String summary() {
        return metadata.documentation().summary();
    }

    /** @return complete operation documentation */
    public OperationDocumentation documentation() {
        return metadata.documentation();
    }

    /** @return validated request schema */
    public OperationSchema inputSchema() {
        return metadata.inputSchema();
    }

    /** @return validated result schema */
    public OperationSchema resultSchema() {
        return metadata.resultSchema();
    }

    /** @return explicit operation safety policy */
    public OperationSafetyPolicy safety() {
        return metadata.safety();
    }

    /** Returns a descriptor with the same executable identity and new manifest metadata. */
    public OperationDescriptor withMetadata(OperationDescriptorMetadata replacement) {
        return new OperationDescriptor(toolName, action, requestType, resultType, executableOwner, trace, replacement);
    }
}
