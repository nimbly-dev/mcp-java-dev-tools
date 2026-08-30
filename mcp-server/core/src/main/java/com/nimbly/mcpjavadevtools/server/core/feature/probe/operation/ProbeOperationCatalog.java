package com.nimbly.mcpjavadevtools.server.core.feature.probe.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.ProbeFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.operation.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationCatalog;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceEntry;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Complete Catalog-Describe-Execute surface for the Probe Feature. */
public final class ProbeOperationCatalog {

    /** Stable MCP Tool name owned by this catalog. */
    public static final String TOOL_NAME = "probe";
    private static final OperationExposure DEFAULT_EXPOSURE = new OperationExposure(
            TOOL_NAME,
            ProbeOperationCatalog.class.getName(),
            List.of("check", "status", "reset", "wait_for_hit", "capture", "actuate", "profiler"));
    private static final OperationTraceMetadata DEFAULT_TRACE = new OperationTraceMetadata(
            ProbeOperationCatalog.class.getName(),
            ProbeOperationCatalog.class.getName(),
            ProbeFeature.class.getName(),
            ProbeOperationCatalog.class.getName(),
            ProbeOperationCatalog.class.getName(),
            "probe_runtime",
            Map.of("actionHandlers", ProbeActionHandler.class.getName()));

    private final OperationCatalog<ProbeAction, ProbeRequest, ProbeResult> catalog;

    /** Creates a complete catalog from Application exposure and trace metadata. */
    public ProbeOperationCatalog(
            List<? extends ProbeActionHandler> handlers,
            OperationExposure exposure,
            OperationTraceMetadata trace) {
        this.catalog = new OperationCatalog<>(
                ProbeAction.class,
                Objects.requireNonNull(exposure, "exposure must not be null"),
                ProbeOperationCatalog.class,
                registrations(handlers, exposure, Objects.requireNonNull(trace, "trace must not be null")));
    }

    /** Creates a complete Core-test catalog with deterministic local metadata. */
    public ProbeOperationCatalog(List<? extends ProbeActionHandler> handlers) {
        this(handlers, DEFAULT_EXPOSURE, DEFAULT_TRACE);
    }

    /** @return immutable descriptors in closed action order */
    public List<OperationDescriptor> catalog() {
        return catalog.catalog();
    }

    /** @return immutable typed operations for aggregate manifest composition */
    public List<Operation<ProbeAction, ProbeRequest, ProbeResult>> operations() {
        return catalog.operations();
    }

    /** @param action selected Probe action @return its descriptor */
    public OperationDescriptor describe(ProbeAction action) {
        return catalog.describe(action);
    }

    /** Executes exactly the registered Probe action. */
    public ProbeResult execute(ProbeAction action, ProbeRequest request) {
        return catalog.execute(action, request);
    }

    /** @return generated Tool-to-Operation inventory */
    public List<OperationTraceEntry> traceInventory() {
        return catalog.traceInventory();
    }

    static List<ProbeOperation> registrations(
            List<? extends ProbeActionHandler> handlers,
            OperationExposure exposure,
            OperationTraceMetadata trace) {
        Objects.requireNonNull(handlers, "handlers must not be null");
        return handlers.stream()
                .map(handler -> new ProbeOperation(handler, exposure, trace))
                .toList();
    }
}
