package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution;

import com.nimbly.mcpjavadevtools.server.core.dispatch.EnumActionDispatcher;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.action.TransportExecutionActionHandler;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.TransportExecutionAction;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.request.TransportExecutionRequest;
import java.util.List;
import java.util.Objects;

/** COMPATIBILITY_RETAINED_UNTIL_611: used by TransportExecuteMcpTool; delete with that #611 adapter. */
public final class DefaultTransportExecutionFeature implements TransportExecutionFeature {

    private final List<? extends TransportExecutionActionHandler> handlers;

    public DefaultTransportExecutionFeature(List<? extends TransportExecutionActionHandler> handlers) {
        this.handlers = List.copyOf(handlers);
        new EnumActionDispatcher<>(TransportExecutionAction.class, this.handlers);
    }

    public TransportExecutionActionHandler operationOwner(TransportExecutionAction action) {
        return handlers.stream().filter(handler -> handler.action() == action).findFirst().orElseThrow();
    }

    /** {@inheritDoc} */
    @Override
    public ExecuteTransportResult execute(TransportExecutionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return operationOwner(request.action()).execute(request);
    }
}
