package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.List;

/**
 * Complete production JVM lifecycle Feature implementation.
 */
public final class DefaultJvmLifecycleFeature implements JvmLifecycleFeature {

    private final EnumActionDispatcher<JvmLifecycleAction, JvmLifecycleRequest, JvmLifecycleResult>
            dispatcher;

    /** Creates a complete dispatcher for all public lifecycle actions. */
    public DefaultJvmLifecycleFeature(List<? extends JvmLifecycleActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(JvmLifecycleAction.class, handlers);
    }

    @Override
    public JvmLifecycleResult execute(JvmLifecycleRequest request) {
        if (request == null || request.action() == null) {
            return JvmLifecycleResult.blocked("jvm_lifecycle_request_invalid");
        }
        return dispatcher.dispatch(request.action(), request);
    }
}
