package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.JvmLifecycleFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.operation.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete Catalog-Describe-Execute surface for the JVM lifecycle Feature. */
public final class JvmLifecycleOperationCatalog {

    /** Stable MCP Tool name owned by this catalog. */
    public static final String TOOL_NAME = "jvm_lifecycle";
    private static final OperationExposure DEFAULT_EXPOSURE = new OperationExposure(
            TOOL_NAME,
            JvmLifecycleOperationCatalog.class.getName(),
            List.of("list_jvms", "attach", "deactivate"));
    private static final OperationTraceMetadata DEFAULT_TRACE = new OperationTraceMetadata(
            JvmLifecycleOperationCatalog.class.getName(),
            JvmLifecycleOperationCatalog.class.getName(),
            JvmLifecycleFeature.class.getName(),
            JvmLifecycleOperationCatalog.class.getName(),
            JvmLifecycleOperationCatalog.class.getName(),
            "jvm_lifecycle",
            Map.of("actionHandlers", JvmLifecycleActionHandler.class.getName()));

    private final OperationCatalog<JvmLifecycleAction, JvmLifecycleRequest, JvmLifecycleResult> catalog;

    /** Creates a complete catalog from Application exposure and trace metadata. */
    public JvmLifecycleOperationCatalog(
            List<? extends JvmLifecycleActionHandler> handlers,
            OperationExposure exposure,
            OperationTraceMetadata trace) {
        this.catalog = new OperationCatalog<>(
                JvmLifecycleAction.class,
                Objects.requireNonNull(exposure, "exposure must not be null"),
                JvmLifecycleOperationCatalog.class,
                registrations(handlers, exposure, Objects.requireNonNull(trace, "trace must not be null")));
    }

    /** Creates a complete Core-test catalog with deterministic local metadata. */
    public JvmLifecycleOperationCatalog(List<? extends JvmLifecycleActionHandler> handlers) {
        this(handlers, DEFAULT_EXPOSURE, DEFAULT_TRACE);
    }

    /** @return immutable descriptors in closed action order */
    public List<OperationDescriptor> catalog() {
        return catalog.catalog();
    }

    /** @return immutable typed operations for aggregate manifest composition */
    public List<Operation<JvmLifecycleAction, JvmLifecycleRequest, JvmLifecycleResult>> operations() {
        return catalog.operations();
    }

    /** @param action selected lifecycle action @return its descriptor */
    public OperationDescriptor describe(JvmLifecycleAction action) {
        return catalog.describe(action);
    }

    /** Executes exactly the registered lifecycle action. */
    public JvmLifecycleResult execute(JvmLifecycleAction action, JvmLifecycleRequest request) {
        return catalog.execute(action, request);
    }

    /** @return generated Tool-to-Operation inventory */
    public List<OperationTraceEntry> traceInventory() {
        return catalog.traceInventory();
    }

    static List<JvmLifecycleOperation> registrations(
            List<? extends JvmLifecycleActionHandler> handlers,
            OperationExposure exposure,
            OperationTraceMetadata trace) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        return handlers.stream()
                .map(handler -> new JvmLifecycleOperation(handler, exposure, trace))
                .toList();
    }
}
