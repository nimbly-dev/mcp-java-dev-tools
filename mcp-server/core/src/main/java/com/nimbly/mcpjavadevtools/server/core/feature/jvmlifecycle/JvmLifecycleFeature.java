package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;

/**
 * Intentional Spring-independent entry point for JVM lifecycle operations.
 */
public interface JvmLifecycleFeature {

    /**
     * Executes one typed JVM lifecycle action.
     *
     * @param request feature-owned request
     * @return deterministic lifecycle result
     */
    JvmLifecycleResult execute(JvmLifecycleRequest request);
}
