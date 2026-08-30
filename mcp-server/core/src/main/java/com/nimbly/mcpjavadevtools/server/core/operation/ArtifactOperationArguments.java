package com.nimbly.mcpjavadevtools.server.core.operation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import java.util.Objects;

/** Canonical artifact argument envelope after the artifact discriminator is removed. */
public record ArtifactOperationArguments(JsonNode input) {

    public ArtifactOperationArguments {
        input = Objects.requireNonNull(input, "artifact input must not be null").deepCopy();
        if (input.isNull()) {
            input = NullNode.getInstance();
        }
    }
}
