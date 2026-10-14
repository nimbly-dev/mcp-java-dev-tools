package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload;

import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.workload.PerformanceHttpWorkload;
import com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.model.workload.PerformanceWorkloadResult;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.TransportExecutionFeature;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.model.action.execute.ExecuteTransportRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.transportexecution.protocol.TransportProtocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Concurrency-based builtin workload through the existing wrapped Transport owner. */
public class BuiltinPerformanceWorkload {
    private static final int MAX_SAMPLES = 100_000;
    private final TransportExecutionFeature transport;

    public BuiltinPerformanceWorkload(TransportExecutionFeature transport) {
        this.transport = transport;
    }

    public PerformanceWorkloadResult execute(PerformanceHttpWorkload workload) {
        if (transport == null) {
            return PerformanceWorkloadResult.blocked("performance_transport_unavailable");
        }
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<String> blocked = new AtomicReference<>();
        long deadline = System.nanoTime() + workload.durationSeconds() * 1_000_000_000L;
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < workload.concurrency(); index++) {
                long delay = workload.rampUpSeconds() * 1000L * index / workload.concurrency();
                tasks.add(workers.submit(() -> worker(workload, deadline, delay, latencies, failures, blocked)));
            }
            try {
                for (Future<?> task : tasks) {
                    task.get();
                }
            } catch (InterruptedException exception) {
                tasks.forEach(task -> task.cancel(true));
                Thread.currentThread().interrupt();
                blocked.compareAndSet(null, "performance_workload_interrupted");
            } catch (ExecutionException exception) {
                tasks.forEach(task -> task.cancel(true));
                blocked.compareAndSet(null, "performance_workload_failed");
            }
        }
        if (blocked.get() != null) {
            return PerformanceWorkloadResult.blocked(blocked.get());
        }
        return new PerformanceWorkloadResult(true, "ok", latencies.size(), failures.get(), latencies, Map.of());
    }

    private void worker(PerformanceHttpWorkload workload, long deadline, long delay, List<Long> latencies,
            AtomicInteger failures, AtomicReference<String> blocked) {
        try {
            Thread.sleep(Math.min(delay, workload.durationSeconds() * 1000L));
            while (System.nanoTime() < deadline && blocked.get() == null) {
                var result = transport.execute(new ExecuteTransportRequest(TransportProtocol.HTTP, request(workload), true));
                if (!List.of("pass", "fail_http").contains(result.status())) {
                    blocked.compareAndSet(null, result.reasonCode());
                    return;
                }
                synchronized (latencies) {
                    if (latencies.size() >= MAX_SAMPLES) {
                        blocked.compareAndSet(null, "performance_workload_sample_limit_exceeded");
                        return;
                    }
                    latencies.add(Math.max(1L, Math.round(result.durationMs())));
                }
                if (!"pass".equals(result.status())) {
                    failures.incrementAndGet();
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            blocked.compareAndSet(null, "performance_workload_interrupted");
        }
    }

    private static Map<String, Object> request(PerformanceHttpWorkload workload) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", workload.method());
        request.put("url", workload.url());
        request.put("headers", workload.headers());
        request.put("timeoutMs", workload.timeoutMs());
        if (workload.body() != null) {
            request.put("body", workload.body());
        }
        return request;
    }
}
