package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;

/**
 * Common typed request contract for JVM lifecycle actions.
 */
public interface JvmLifecycleRequest {

    /**
     * Returns the selected public action.
     *
     * @return action
     */
    JvmLifecycleAction action();
}
