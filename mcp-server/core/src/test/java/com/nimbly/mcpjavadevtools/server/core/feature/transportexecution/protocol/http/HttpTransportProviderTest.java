package com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

class HttpTransportProviderTest {

    @Test
    void executesLoopbackHttpAndRedactsResponseHeadersAndJsonPreview() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=secret-value");
            respond(exchange, 200, "{\"token\":\"secret-value\",\"safe\":\"ok\"}");
        });
        try {
            ExecuteTransportResult result = provider().execute(request(server, Map.of(
                    "method", "GET",
                    "headers", Map.of("Authorization", "Bearer request-secret"))));

            assertThat(result.status()).isEqualTo("pass");
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.headers()).containsEntry("set-cookie", "[REDACTED]");
            assertThat(result.bodyPreview()).contains("[REDACTED]").doesNotContain("secret-value");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsExternalHostsAndDisallowedSchemesBeforeNetworkAccess() {
        ExecuteTransportResult external = provider().execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP,
                Map.of("method", "GET", "url", "http://example.com"),
                true));
        ExecuteTransportResult file = provider().execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP,
                Map.of("method", "GET", "url", "file:///tmp/test"),
                true));

        assertThat(external.reasonCode()).isEqualTo("http_host_not_allowed");
        assertThat(file.reasonCode()).isEqualTo("http_scheme_not_allowed");
    }

    @Test
    void rejectsInvalidTimeoutAndOversizedRequestBody() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "ok"));
        try {
            ExecuteTransportResult timeout = provider().execute(request(server, Map.of(
                    "method", "GET", "timeoutMs", 300001)));
            ExecuteTransportResult body = provider().execute(request(server, Map.of(
                    "method", "POST", "body", "x".repeat(4 * 1024 * 1024 + 1))));

            assertThat(timeout.reasonCode()).isEqualTo("http_timeout_invalid");
            assertThat(body.reasonCode()).isEqualTo("http_request_body_too_large");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesJsonContentTypeToStructuredBodiesAndTimesOutBoundedRequests() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            respond(exchange, 200, "ok");
        });
        try {
            ExecuteTransportResult structured = provider().execute(request(server, Map.of(
                    "method", "POST", "body", Map.of("id", "bounded"))));
            assertThat(structured.status()).isEqualTo("pass");
            assertThat(contentType.get()).isEqualTo("application/json");
        } finally {
            server.stop(0);
        }

        HttpServer slow = server(exchange -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "late");
        });
        try {
            ExecuteTransportResult timeout = provider().execute(request(slow, Map.of(
                    "method", "GET", "timeoutMs", 10)));
            assertThat(timeout.reasonCode()).isEqualTo("transport_request_timeout");
        } finally {
            slow.stop(0);
        }
    }

    @Test
    void removesAuthorizationAndCookieOnCrossOriginRedirect() throws Exception {
        HttpServer target = server(exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
            respond(exchange, 200, "redirected");
        });
        HttpServer source = server(exchange -> {
            exchange.getResponseHeaders().add("Location", url(target));
            respond(exchange, 302, "redirect");
        });
        try {
            ExecuteTransportResult result = provider().execute(request(source, Map.of(
                    "method", "GET",
                    "headers", Map.of("Authorization", "Bearer request-secret", "Cookie", "session=secret"))));

            assertThat(result.status()).isEqualTo("pass");
            assertThat(result.bodyPreview()).isEqualTo("redirected");
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }

    @Test
    void appliesOneTimeoutBudgetAcrossRedirects() throws Exception {
        HttpServer target = server(exchange -> {
            pause(120);
            respond(exchange, 200, "late target");
        });
        HttpServer source = server(exchange -> {
            pause(120);
            exchange.getResponseHeaders().add("Location", url(target));
            respond(exchange, 302, "redirect");
        });
        try {
            ExecuteTransportResult result = provider().execute(request(source, Map.of(
                    "method", "GET", "timeoutMs", 180)));

            assertThat(result.status()).isEqualTo("blocked_runtime");
            assertThat(result.reasonCode()).isEqualTo("transport_request_timeout");
            assertThat(result.durationMs()).isLessThan(280);
        } finally {
            source.stop(0);
            target.stop(0);
        }
    }

    @Test
    void appliesTimeoutWhileConsumingTheResponseBody() throws Exception {
        HttpServer server = server(exchange -> {
            byte[] bytes = "late body".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            pause(150);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        try {
            ExecuteTransportResult result = provider().execute(request(server, Map.of(
                    "method", "GET", "timeoutMs", 30)));

            assertThat(result.status()).isEqualTo("blocked_runtime");
            assertThat(result.reasonCode()).isEqualTo("transport_request_timeout");
            assertThat(result.durationMs()).isLessThan(140);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsStableCancellationAndCancelsUnderlyingExchangeWhenInterrupted() throws Exception {
        PendingHttpClient client = new PendingHttpClient();
        HttpTransportProvider provider = provider(client);
        AtomicReference<ExecuteTransportResult> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            result.set(provider.execute(new ExecuteTransportRequest(
                    TransportProtocol.HTTP,
                    Map.of("method", "GET", "url", "http://127.0.0.1/cancellation-check"),
                    true)));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        CompletableFuture<?> exchange = client.awaitPending();
        worker.interrupt();
        worker.join(2_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get().status()).isEqualTo("blocked_runtime");
        assertThat(result.get().reasonCode()).isEqualTo("transport_request_cancelled");
        assertThat(interrupted.get()).isTrue();
        assertThat(exchange.isCancelled()).isTrue();
    }

    @Test
    void rejectsResponseLargerThanFourMiB() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "x".repeat(4 * 1024 * 1024 + 1)));
        try {
            ExecuteTransportResult result = provider().execute(request(server, Map.of("method", "GET")));

            assertThat(result.status()).isEqualTo("blocked_runtime");
            assertThat(result.reasonCode()).isEqualTo("http_response_body_too_large");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redactsSensitiveQueryMetadataAndRejectsUriUserInfo() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ExecuteTransportResult unavailable = provider().execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP,
                Map.of("method", "GET", "url", "http://127.0.0.1:" + port + "/?token=secret-value"),
                true));
        assertThat(unavailable.reasonMeta().get("url").toString()).contains("token=[REDACTED]")
                .doesNotContain("secret-value");

        ExecuteTransportResult userInfo = provider().execute(new ExecuteTransportRequest(
                TransportProtocol.HTTP,
                Map.of("method", "GET", "url", "http://user:secret@127.0.0.1:" + port),
                true));
        assertThat(userInfo.reasonCode()).isEqualTo("http_user_info_not_allowed");
        assertThat(userInfo.toString()).doesNotContain("secret");
    }

    private HttpTransportProvider provider() {
        return provider(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private HttpTransportProvider provider(HttpClient client) {
        HttpTransportSafetyPolicy policy = new HttpTransportSafetyPolicy(java.util.Set.of());
        HttpSensitiveDataRedactor redactor = new HttpSensitiveDataRedactor();
        return new HttpTransportProvider(
                new HttpRequestValidator(policy, redactor, new ObjectMapper()),
                new HttpRedirectResponseExecutor(client, policy, redactor));
    }

    private ExecuteTransportRequest request(HttpServer server, Map<String, Object> values) {
        Map<String, Object> request = new LinkedHashMap<>(values);
        request.putIfAbsent("url", url(server));
        return new ExecuteTransportRequest(TransportProtocol.HTTP, request, true);
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class PendingHttpClient extends HttpClient {

        private final CountDownLatch requested = new CountDownLatch(1);
        private final AtomicReference<CompletableFuture<?>> pending = new AtomicReference<>();

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("send is not used by this test client");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            CompletableFuture<HttpResponse<T>> future = new CompletableFuture<>();
            pending.set(future);
            requested.countDown();
            return future;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        CompletableFuture<?> awaitPending() throws InterruptedException {
            assertThat(requested.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return pending.get();
        }
    }
}
