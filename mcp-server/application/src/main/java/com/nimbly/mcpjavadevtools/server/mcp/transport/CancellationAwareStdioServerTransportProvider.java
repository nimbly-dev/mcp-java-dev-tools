package com.nimbly.mcpjavadevtools.server.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Adds MCP request cancellation to the SDK's STDIO transport session. */
public class CancellationAwareStdioServerTransportProvider implements McpServerTransportProvider {

    private final StdioServerTransportProvider delegate;

    public CancellationAwareStdioServerTransportProvider(McpJsonMapper jsonMapper) {
        this.delegate = new StdioServerTransportProvider(jsonMapper);
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory sessionFactory) {
        delegate.setSessionFactory(transport -> new CancellationAwareSession(
                sessionFactory.create(transport), transport));
    }

    @Override
    public Mono<Void> notifyClients(String method, Object params) {
        return delegate.notifyClients(method, params);
    }

    @Override
    public Mono<Void> notifyClient(String sessionId, String method, Object params) {
        return delegate.notifyClient(sessionId, method, params);
    }

    @Override
    public Mono<Void> closeGracefully() {
        return delegate.closeGracefully();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public java.util.List<String> protocolVersions() {
        return delegate.protocolVersions();
    }

    private static final class CancellationAwareSession extends McpServerSession {

        private static final String CANCELLED_NOTIFICATION = "notifications/cancelled";

        private final McpServerSession delegate;
        private final Map<Object, Sinks.One<Void>> activeRequests = new ConcurrentHashMap<>();

        private CancellationAwareSession(McpServerSession delegate, McpServerTransport transport) {
            super(delegate.getId(), Duration.ZERO, transport,
                    request -> Mono.error(new IllegalStateException("delegate session owns initialization")),
                    Map.<String, McpRequestHandler<?>>of(),
                    Map.<String, McpNotificationHandler>of());
            this.delegate = delegate;
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public void init(McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo) {
            delegate.init(clientCapabilities, clientInfo);
        }

        @Override
        public void setMinLoggingLevel(McpSchema.LoggingLevel minLoggingLevel) {
            delegate.setMinLoggingLevel(minLoggingLevel);
        }

        @Override
        public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel level) {
            return delegate.isNotificationForLevelAllowed(level);
        }

        @Override
        public <T> Mono<T> sendRequest(String method, Object params, TypeRef<T> typeRef) {
            return delegate.sendRequest(method, params, typeRef);
        }

        @Override
        public Mono<Void> sendNotification(String method, Object params) {
            return delegate.sendNotification(method, params);
        }

        @Override
        public Mono<Void> handle(McpSchema.JSONRPCMessage message) {
            if (message instanceof McpSchema.JSONRPCNotification notification
                    && CANCELLED_NOTIFICATION.equals(notification.method())) {
                cancelRequest(notification.params());
                return Mono.empty();
            }
            if (message instanceof McpSchema.JSONRPCRequest request
                    && !McpSchema.METHOD_INITIALIZE.equals(request.method())) {
                return trackRequest(request);
            }
            return delegate.handle(message);
        }

        @Override
        public Mono<Void> closeGracefully() {
            cancelActiveRequests();
            return delegate.closeGracefully();
        }

        @Override
        public void close() {
            cancelActiveRequests();
            delegate.close();
        }

        private Mono<Void> trackRequest(McpSchema.JSONRPCRequest request) {
            Object requestId = request.id();
            if (requestId == null) {
                return delegate.handle(request);
            }
            Sinks.One<Void> cancellation = Sinks.one();
            return delegate.handle(request)
                    .takeUntilOther(cancellation.asMono())
                    .doOnSubscribe(subscription -> activeRequests.put(requestId, cancellation))
                    .doFinally(signal -> activeRequests.remove(requestId, cancellation));
        }

        private void cancelRequest(Object params) {
            if (!(params instanceof Map<?, ?> values)) {
                return;
            }
            Object requestId = values.get("requestId");
            if (requestId == null) {
                return;
            }
            Sinks.One<Void> cancellation = activeRequests.remove(requestId);
            if (cancellation != null) {
                cancellation.tryEmitEmpty();
            }
        }

        private void cancelActiveRequests() {
            activeRequests.values().forEach(Sinks.One::tryEmitEmpty);
            activeRequests.clear();
        }
    }
}
