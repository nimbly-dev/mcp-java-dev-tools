package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.listjvms;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;

/**
 * Request for bounded local JVM discovery.
 */
public record ListJvmsRequest() implements JvmLifecycleRequest {

    @Override
    public JvmLifecycleAction action() {
        return JvmLifecycleAction.LIST_JVMS;
    }
}
