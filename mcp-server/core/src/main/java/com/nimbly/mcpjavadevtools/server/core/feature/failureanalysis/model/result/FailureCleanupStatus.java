package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.result;

/** Cleanup ownership values preserved from the Failure Lens workflow. */
public enum FailureCleanupStatus {
    CLEANUP_CONFIRMED("cleanup_confirmed"),
    CLEANUP_INCOMPLETE("cleanup_incomplete"),
    EXTERNAL_WORKFLOW_OWNED("external_workflow_owned");

    private final String value;

    FailureCleanupStatus(String value) {
        this.value = value;
    }

    /** @return stable cleanup status */
    public String value() {
        return value;
    }
}
