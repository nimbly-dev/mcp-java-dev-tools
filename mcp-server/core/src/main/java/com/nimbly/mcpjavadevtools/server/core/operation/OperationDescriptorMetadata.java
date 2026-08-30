package com.nimbly.mcpjavadevtools.server.core.operation;


import java.util.Objects;

/** Versioned metadata attached to an aggregate operation descriptor. */
public record OperationDescriptorMetadata(
        OperationId operationId,
        String classification,
        OperationDocumentation documentation,
        OperationSchema inputSchema,
        OperationSchema resultSchema,
        OperationSafetyPolicy safety) {

    /** Validates complete aggregate descriptor metadata. */
    public OperationDescriptorMetadata {
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(classification, "classification must not be null");
        Objects.requireNonNull(documentation, "documentation must not be null");
        Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        Objects.requireNonNull(resultSchema, "resultSchema must not be null");
        Objects.requireNonNull(safety, "safety must not be null");
        if (classification.isBlank()) {
            throw new IllegalArgumentException("classification must not be blank");
        }
        if (!classification.equals(documentation.classification())) {
            throw new IllegalArgumentException("descriptor classification does not match documentation");
        }
    }

    /** Creates compatibility metadata for the pre-manifest descriptor shape. */
    public static OperationDescriptorMetadata legacy(
            String toolName, String action, String sideEffect) {
        OperationId id = OperationId.fromLegacy(toolName, action);
        OperationDocumentation documentation = OperationDocumentation.legacy(id, sideEffect);
        return new OperationDescriptorMetadata(
                id,
                documentation.classification(),
                documentation,
                OperationSchema.empty(),
                OperationSchema.empty(),
                documentation.safety());
    }
}
