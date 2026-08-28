package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease;

/**
 * Provides exclusive ownership for one active orchestration suite run.
 *
 * <p>The Application owns the lifecycle of an implementation. Callers use
 * this boundary only to prevent concurrent execution of the same suite run.</p>
 */
public interface ExecutionRunLease {

    /** Attempts to acquire exclusive ownership of the selected suite run. */
    boolean acquire(String projectName, String suiteRunId);

    /** Releases a previously acquired suite-run ownership claim. */
    void release(String projectName, String suiteRunId);
}
