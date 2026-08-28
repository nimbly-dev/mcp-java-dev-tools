package com.nimbly.mcpjavadevtools.server.core.feature.executionorchestration.lease;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Process-scoped exclusive lease implementation for active suite calls. */
public final class InMemoryExecutionRunLease implements ExecutionRunLease {

    private final Set<String> active = ConcurrentHashMap.newKeySet();

    /** {@inheritDoc} */
    @Override
    public boolean acquire(String projectName, String suiteRunId) {
        return active.add(key(projectName, suiteRunId));
    }

    /** {@inheritDoc} */
    @Override
    public void release(String projectName, String suiteRunId) {
        active.remove(key(projectName, suiteRunId));
    }

    private static String key(String projectName, String suiteRunId) {
        return projectName + "\u0000" + suiteRunId;
    }
}
