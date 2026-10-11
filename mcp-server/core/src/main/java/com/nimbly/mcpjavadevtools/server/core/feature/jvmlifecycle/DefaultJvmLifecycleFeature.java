package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete production JVM lifecycle Feature implementation.
 */
public class DefaultJvmLifecycleFeature implements JvmLifecycleFeature {

    private final Map<JvmLifecycleAction, JvmLifecycleActionHandler> handlers;

    /** Creates a complete catalog for all public lifecycle actions. */
    public DefaultJvmLifecycleFeature(List<? extends JvmLifecycleActionHandler> handlers) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        Map<JvmLifecycleAction, JvmLifecycleActionHandler> indexed =
                new EnumMap<>(JvmLifecycleAction.class);
        for (JvmLifecycleActionHandler handler : handlers) {
            JvmLifecycleActionHandler checked = Objects.requireNonNull(
                    handler, "handler must not be null");
            JvmLifecycleAction action = Objects.requireNonNull(
                    checked.action(), "handler action must not be null");
            if (indexed.putIfAbsent(action, checked) != null) {
                throw new IllegalArgumentException(
                        "Duplicate JVM lifecycle action owner: " + action.value());
            }
        }
        if (indexed.size() != JvmLifecycleAction.values().length) {
            throw new IllegalArgumentException(
                    "JVM lifecycle handlers must cover every JVM lifecycle action");
        }
        this.handlers = Map.copyOf(indexed);
    }

    @Override
    public JvmLifecycleResult execute(JvmLifecycleRequest request) {
        if (request == null || request.action() == null) {
            return JvmLifecycleResult.blocked("jvm_lifecycle_request_invalid");
        }
        return handlers.get(request.action()).execute(request);
    }
}
