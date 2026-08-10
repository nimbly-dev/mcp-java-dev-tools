package com.nimbly.mcpjavadevtools.server.lifecycle;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceContext {

    private volatile WorkspaceSnapshot snapshot;

    @Autowired
    public WorkspaceContext(ApplicationArguments arguments) {
        this(WorkspaceResolver.initial(arguments, System.getenv(), Path.of("")));
    }

    WorkspaceContext(WorkspaceSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public WorkspaceSnapshot snapshot() {
        return snapshot;
    }

    public void refreshFrom(McpSyncRequestContext requestContext) {
        if (!requestContext.rootsEnabled()) {
            return;
        }
        try {
            refreshRoots(requestContext.roots().roots());
        } catch (RuntimeException exception) {
            handleRootsFailure(exception);
        }
    }

    public void refreshFrom(McpSyncServerExchange exchange) {
        try {
            refreshRoots(exchange.listRoots().roots());
        } catch (RuntimeException exception) {
            handleRootsFailure(exception);
        }
    }

    public void refreshRoots(List<McpSchema.Root> roots) {
        snapshot = WorkspaceResolver.fromRoots(roots);
    }

    private void handleRootsFailure(RuntimeException exception) {
        if (isExpectedRootsFailure(exception)) {
            snapshot = WorkspaceResolver.rootsUnavailable(snapshot);
            return;
        }
        throw exception;
    }

    private boolean isExpectedRootsFailure(RuntimeException exception) {
        return exception instanceof McpError
                || exception instanceof McpTransportException
                || exception instanceof McpTransportSessionClosedException
                || exception instanceof McpTransportSessionNotFoundException;
    }
}
