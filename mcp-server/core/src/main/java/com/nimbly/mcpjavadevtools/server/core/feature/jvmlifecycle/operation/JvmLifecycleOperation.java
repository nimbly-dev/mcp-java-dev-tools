package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.operation;

import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.action.JvmLifecycleActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.action.JvmLifecycleAction;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.request.JvmLifecycleRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.model.result.JvmLifecycleResult;
import com.nimbly.mcpjavadevtools.server.core.operation.Operation;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationDescriptor;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationExposure;
import com.nimbly.mcpjavadevtools.server.core.operation.OperationTraceMetadata;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Catalog binding for one concrete JVM lifecycle action owner. */
final class JvmLifecycleOperation implements Operation<
        JvmLifecycleAction, JvmLifecycleRequest, JvmLifecycleResult> {

    private final JvmLifecycleAction action;
    private final JvmLifecycleActionHandler handler;
    private final OperationDescriptor descriptor;

    JvmLifecycleOperation(
            JvmLifecycleActionHandler handler,
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
                JvmLifecycleOperationEffects.sideEffect(action),
                roles);
        this.descriptor = new OperationDescriptor(
                checkedExposure.toolName(),
                action.value(),
                JvmLifecycleRequest.class.getName(),
                JvmLifecycleResult.class.getName(),
                handler.getClass().getName(),
                operationTrace);
    }

    @Override
    public JvmLifecycleAction operationId() {
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
    public JvmLifecycleResult execute(JvmLifecycleRequest input) {
        return handler.execute(input);
    }
}
