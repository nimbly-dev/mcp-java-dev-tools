package com.nimbly.mcpjavadevtools.server.core.feature.probe.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.probe.action.ProbeActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.action.ProbeAction;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.request.ProbeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.probe.model.result.ProbeResult;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.manifest.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.catalog.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.trace.OperationTraceMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Catalog binding for one concrete Probe action owner. */
final class ProbeOperation implements Operation<ProbeAction, ProbeRequest, ProbeResult> {

    private final ProbeAction action;
    private final ProbeActionHandler handler;
    private final OperationDescriptor descriptor;

    ProbeOperation(
            ProbeActionHandler handler,
            OperationExposure exposure,
            OperationTraceMetadata trace) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.action = Objects.requireNonNull(handler.action(), "handler action must not be null");
        OperationExposure checkedExposure = Objects.requireNonNull(exposure, "exposure must not be null");
        OperationTraceMetadata checkedTrace = Objects.requireNonNull(trace, "trace must not be null");
        Map<String, String> roles = new LinkedHashMap<>(checkedTrace.collaboratorRoles());
        roles.put("actionOwner", handler.getClass().getName());
        OperationTraceMetadata operationTrace = new OperationTraceMetadata(
                checkedTrace.mcpAdapter(),
                checkedTrace.requestMapper(),
                checkedTrace.coreFeature(),
                checkedTrace.responseMapper(),
                checkedTrace.focusedEvidence(),
                ProbeOperationEffects.sideEffect(action),
                roles);
        this.descriptor = new OperationDescriptor(
                checkedExposure.toolName(),
                action.value(),
                ProbeRequest.class.getName(),
                ProbeResult.class.getName(),
                handler.getClass().getName(),
                operationTrace);
    }

    @Override
    public ProbeAction operationId() {
        return action;
    }

    @Override
    public OperationDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public String executableOwner() {
        return handler.getClass().getName();
    }

    @Override
    public ProbeResult execute(ProbeRequest input) {
        return handler.execute(input);
    }
}
