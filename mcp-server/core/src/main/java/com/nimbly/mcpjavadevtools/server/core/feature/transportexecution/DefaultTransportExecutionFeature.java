package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.TransportExecutionActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request.TransportExecutionRequest;
import java.util.List;
import java.util.Objects;

/** Complete dispatcher-backed Transport Execution Feature implementation. */
public final class DefaultTransportExecutionFeature implements TransportExecutionFeature {

    private final EnumActionDispatcher<
            TransportExecutionAction, TransportExecutionRequest, ExecuteTransportResult> dispatcher;

    /**
     * Creates a complete dispatcher for the internal action allowlist.
     *
     * @param handlers real action implementations
     */
    public DefaultTransportExecutionFeature(List<? extends TransportExecutionActionHandler> handlers) {
        dispatcher = new EnumActionDispatcher<>(TransportExecutionAction.class, handlers);
    }

    /** {@inheritDoc} */
    @Override
    public ExecuteTransportResult execute(TransportExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return dispatcher.dispatch(request.action(), request);
    }
}
