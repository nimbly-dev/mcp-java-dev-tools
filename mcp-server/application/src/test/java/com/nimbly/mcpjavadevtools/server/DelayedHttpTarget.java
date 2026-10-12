package com.nimbly.mcpjavadevtools.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Bounded local HTTP target for transport interruption and timeout acceptance. */
final class DelayedHttpTarget implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final CountDownLatch requestStarted;
    private final CountDownLatch clientDisconnected;

    private DelayedHttpTarget(
            HttpServer server,
            ExecutorService executor,
            CountDownLatch requestStarted,
            CountDownLatch clientDisconnected) {
        this.server = server;
        this.executor = executor;
        this.requestStarted = requestStarted;
        this.clientDisconnected = clientDisconnected;
    }

    static DelayedHttpTarget start() throws IOException {
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch clientDisconnected = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/delay", exchange -> {
            requestStarted.countDown();
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[1024];
                for (int attempt = 0; attempt < 6_000; attempt++) {
                    exchange.getResponseBody().write(chunk);
                    exchange.getResponseBody().flush();
                    Thread.sleep(25);
                }
            } catch (IOException exception) {
                clientDisconnected.countDown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        return new DelayedHttpTarget(server, executor, requestStarted, clientDisconnected);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/delay";
    }

    void awaitRequest() throws InterruptedException {
        assertThat(requestStarted.await(5, TimeUnit.SECONDS))
                .as("the delayed HTTP target must receive the transport request")
                .isTrue();
    }

    void assertStillConnected() throws InterruptedException {
        assertThat(clientDisconnected.await(250, TimeUnit.MILLISECONDS))
                .as("the HTTP exchange must remain active before cancellation")
                .isFalse();
    }

    void awaitClientDisconnect() throws InterruptedException {
        assertThat(clientDisconnected.await(5, TimeUnit.SECONDS))
                .as("cancellation must terminate the in-flight HTTP exchange")
                .isTrue();
    }

    @Override
    public void close() throws InterruptedException {
        server.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).as("streaming target executor cleanup").isTrue();
    }
}
