package com.example.mstatiming;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Independent wall-time ground truth for the MSTA calibration acceptance check. */
public class PerformanceTimingFixture {
    private static volatile String last = "{}";
    private static volatile double sink;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0].substring("--server.port=".length()));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(1));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            try (exchange) {
                if (path.startsWith("/timing/")) {
                    long first = Long.parseLong(path.substring("/timing/".length()));
                    long one = timedFirst(first);
                    long two = 0;
                    long three = 0;
                    long cpu = 0;
                    if (first < 60_000) {
                        long started = System.nanoTime();
                        methodTwo();
                        two = System.nanoTime() - started;
                        started = System.nanoTime();
                        methodThree();
                        three = System.nanoTime() - started;
                        started = System.nanoTime();
                        cpuWork();
                        cpu = System.nanoTime() - started;
                    }
                    last = "{\"methodOne\":" + one / 1_000_000.0 + ",\"methodTwo\":" + two / 1_000_000.0
                            + ",\"methodThree\":" + three / 1_000_000.0 + ",\"cpuWork\":" + cpu / 1_000_000.0 + "}";
                }
                String response = path.equals("/actuator/health") ? "{\"status\":\"up\"}" : last;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    private static long timedFirst(long duration) throws InterruptedException {
        long start = System.nanoTime();
        methodOne(duration);
        return System.nanoTime() - start;
    }

    private static void methodOne(long duration) throws InterruptedException {
        Thread.sleep(duration);
    }

    private static void methodTwo() throws InterruptedException {
        Thread.sleep(10_000);
    }

    private static void methodThree() throws InterruptedException {
        Thread.sleep(15_000);
    }

    private static void cpuWork() {
        double value = 0;
        for (int index = 0; index < 30_000_000; index++) {
            value += Math.sin(index * 0.0001);
        }
        sink = value;
    }
}
