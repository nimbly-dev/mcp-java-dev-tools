package com.nimbly.mcpjavadevtools.server.core.feature.artifactmanagement.model.action;

import java.util.Arrays;
import java.util.Optional;

/** Closed action value set used by Artifact Management requests. */
public enum ArtifactAction {
    READ("read"),
    VALIDATE("validate"),
    UPSERT("upsert"),
    LIST("list"),
    GENERATE("generate"),
    RELOAD("reload"),
    REBUILD("rebuild"),
    BACKFILL("backfill"),
    CUTOVER("cutover"),
    QUERY("query"),
    CLEANUP("cleanup");

    private final String value;

    ArtifactAction(String value) {
        this.value = value;
    }

    /** @return stable MCP value */
    public String value() {
        return value;
    }

    /** @param value boundary value @return matching action */
    public static Optional<ArtifactAction> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(action -> action.value.equals(value)).findFirst();
    }
}
