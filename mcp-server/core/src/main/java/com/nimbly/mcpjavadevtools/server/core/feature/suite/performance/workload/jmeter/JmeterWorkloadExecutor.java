package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Generates, runs, and collects one bounded JMeter workload. */
public final class JmeterWorkloadExecutor {

    private static final Duration HARD_MAXIMUM_DURATION = Duration.ofMinutes(30);
    private final JmeterJmxRenderer renderer;
    private final JmeterProcessRunner processRunner;
    private final JmeterJtlCollector collector;

    /** Creates the executor with its external process and Artifact collaborators. */
    public JmeterWorkloadExecutor(JmeterJmxRenderer renderer, JmeterProcessRunner processRunner, JmeterJtlCollector collector) {
        this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner must not be null");
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
    }

    /** Executes the workload and normalizes missing or failed Artifacts to stable reason codes. */
    public JmeterWorkloadResult execute(JmeterWorkloadRequest request) {
        Path jmxPath = request.runDirectory().resolve("workload.jmeter.jmx");
        Path jtlPath = request.runDirectory().resolve("workload.jmeter.jtl");
        Path logPath = request.runDirectory().resolve("workload.jmeter.log");
        try {
            Files.createDirectories(request.runDirectory());
            Files.writeString(jmxPath, renderer.render(request));
            int exitCode = processRunner.run(request.executable(), request.runDirectory(), jmxPath, jtlPath, logPath, timeout(request));
            if (exitCode != 0) {
                return JmeterWorkloadResult.blocked("performance_jmeter_execution_failed", jmxPath, jtlPath, logPath);
            }
            return result(jmxPath, jtlPath, logPath);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return JmeterWorkloadResult.blocked("performance_jmeter_interrupted", jmxPath, jtlPath, logPath);
        } catch (IOException exception) {
            return JmeterWorkloadResult.blocked("performance_jmeter_artifact_failed", jmxPath, jtlPath, logPath);
        }
    }

    private JmeterWorkloadResult result(Path jmxPath, Path jtlPath, Path logPath) throws IOException {
        if (!Files.isRegularFile(jtlPath)) {
            return JmeterWorkloadResult.blocked("performance_jmeter_results_missing", jmxPath, jtlPath, logPath);
        }
        JmeterJtlCollector.Metrics metrics = collector.collect(jtlPath);
        return metrics.totalRequests() == 0
                ? JmeterWorkloadResult.blocked("performance_jmeter_results_invalid", jmxPath, jtlPath, logPath)
                : new JmeterWorkloadResult(true, "ok", metrics.totalRequests(), metrics.failedRequests(), metrics.latenciesMs(), jmxPath, jtlPath, logPath);
    }

    private static Duration timeout(JmeterWorkloadRequest request) {
        long requestedSeconds = (long) request.durationSeconds() + request.rampUpSeconds() + 30L;
        return Duration.ofSeconds(Math.min(requestedSeconds, HARD_MAXIMUM_DURATION.toSeconds()));
    }
}
