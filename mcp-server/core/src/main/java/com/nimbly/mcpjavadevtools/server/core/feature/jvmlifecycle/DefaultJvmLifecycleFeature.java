package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation.JvmLifecycleOperationCatalog;
import java.util.List;
import java.util.Objects;

/**
 * Complete production JVM lifecycle Feature implementation.
 */
public final class DefaultJvmLifecycleFeature implements JvmLifecycleFeature {

    private final JvmLifecycleOperationCatalog operationCatalog;

    /** Creates a complete catalog for all public lifecycle actions. */
    public DefaultJvmLifecycleFeature(List<? extends JvmLifecycleActionHandler> handlers) {
        this(new JvmLifecycleOperationCatalog(handlers));
    }

    /** Creates the Feature from the complete capability-owned operation catalog. */
    public DefaultJvmLifecycleFeature(JvmLifecycleOperationCatalog operationCatalog) {
        this.operationCatalog = Objects.requireNonNull(
                operationCatalog, "operationCatalog must not be null");
    }

    @Override
    public JvmLifecycleResult execute(JvmLifecycleRequest request) {
        if (request == null || request.action() == null) {
            return JvmLifecycleResult.blocked("jvm_lifecycle_request_invalid");
        }
        return operationCatalog.execute(request.action(), request);
    }
}
