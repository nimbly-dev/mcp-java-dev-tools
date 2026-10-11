package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Transparent loopback observer: every response comes from the real owned Sidecar. */
class ProbeRequestObserver implements AutoCloseable {
    private final HttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger requests = new AtomicInteger();

    ProbeRequestObserver(String sidecar) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(sidecar + exchange.getRequestURI()))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                requests.incrementAndGet();
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                exchange.getResponseBody().write(response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void awaitRequests(int count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (requests.get() < count && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(requests.get()).as("Real Sidecar status reads before cancellation/shutdown").isGreaterThanOrEqualTo(count);
    }

    void assertPollingStopped() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        long lastChange = System.nanoTime();
        int observed = requests.get();
        while (System.nanoTime() < deadline) {
            Thread.sleep(50);
            if (requests.get() != observed) {
                observed = requests.get();
                lastChange = System.nanoTime();
            }
            if (System.nanoTime() - lastChange >= Duration.ofMillis(400).toNanos()) {
                return;
            }
        }
        throw new AssertionError("Cancelled wait did not stop real Sidecar polling within its cancellation grace");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
        client.close();
    }
}
