package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

/** Local observable HTTP boundary; request credentials stay in memory only. */
class TransportHttpTarget implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<Received> requests = new LinkedBlockingQueue<>();

    private TransportHttpTarget(HttpHandler handler) throws IOException {
        this(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0), handler);
    }

    private TransportHttpTarget(HttpServer server, HttpHandler handler) {
        this.server = server;
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
    }

    static TransportHttpTarget start(HttpHandler handler) throws IOException {
        return new TransportHttpTarget(handler);
    }

    static TransportHttpTarget start() throws IOException {
        return start(exchange -> respond(exchange, 200, "{\"safe\":\"observed\"}"));
    }

    static TransportHttpTarget startTls(SSLContext context, HttpHandler handler) throws IOException {
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        return new TransportHttpTarget(server, handler);
    }

    String url(String path) {
        String scheme = server instanceof HttpsServer ? "https" : "http";
        return scheme + "://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    Received received() throws InterruptedException {
        Received value = requests.poll(5, TimeUnit.SECONDS);
        assertThat(value).as("fixture must observe an actual HTTP request").isNotNull();
        return value;
    }

    void assertNoRequest() throws InterruptedException {
        assertThat(requests.poll(200, TimeUnit.MILLISECONDS))
                .as("rejected operation must not contact the fixture").isNull();
    }

    static void respond(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("X-Fixture", "observed");
        if (exchange.getRequestMethod().equals("HEAD") || status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Override
    public void close() throws InterruptedException {
        server.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("HTTP fixture executor cleanup").isTrue();
    }

    record Received(String method, String uri, Map<String, List<String>> headers, String body) {
        @Override
        public String toString() {
            return "Recorded HTTP request (payload and credentials omitted)";
        }

        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> String.join(",", entry.getValue())).findFirst().orElse(null);
        }
    }
}
