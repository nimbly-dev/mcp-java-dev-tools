package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

/**
 * Core-owned boundary for the existing repository lifecycle helper.
 */
@FunctionalInterface
public interface JvmLifecycleHelper {

    /**
     * Executes one bounded helper operation.
     *
     * @param request helper request
     * @return normalized helper result
     */
    JvmLifecycleHelperResult execute(JvmLifecycleHelperRequest request);
}
