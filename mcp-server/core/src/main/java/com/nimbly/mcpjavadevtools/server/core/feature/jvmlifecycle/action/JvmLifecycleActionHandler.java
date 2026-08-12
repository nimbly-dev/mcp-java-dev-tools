package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action;

import com.nimbly.mcpjavadevtools.server.core.dispatch.ActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;

/**
 * Typed alias for one JVM lifecycle action implementation.
 */
public interface JvmLifecycleActionHandler
        extends ActionHandler<JvmLifecycleAction, JvmLifecycleRequest, JvmLifecycleResult> {
}
