package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.artifact;

import java.util.Map;

/** Expected Artifact operation failure that must become a deterministic result. */
public final class ArtifactOperationException extends RuntimeException {

    private final String reasonCode;
    private final Map<String, Object> metadata;

    /** Creates a sanitized expected Artifact failure. */
    public ArtifactOperationException(String reasonCode, String message) {
        this(reasonCode, message, Map.of());
    }

    /** Creates a sanitized expected Artifact failure with bounded metadata. */
    public ArtifactOperationException(String reasonCode, String message, Map<String, Object> metadata) {
        super(message);
        this.reasonCode = reasonCode;
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** @return stable public reason code */
    public String reasonCode() {
        return reasonCode;
    }

    /** @return bounded public metadata */
    public Map<String, Object> metadata() {
        return metadata;
    }
}
