package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Process-backed JMeter runner with a hard execution timeout. */
public final class DefaultJmeterProcessRunner implements JmeterProcessRunner {

    /** Runs JMeter without routing subprocess output to the MCP protocol channel. */
    @Override
    public int run(String executable, Path runDirectory, Path jmxPath, Path jtlPath, Path logPath, Duration timeout)
            throws IOException, InterruptedException {
        return start(command(executable, jmxPath, jtlPath, logPath), runDirectory, logPath, timeout, Map.of());
    }

    @Override
    public int run(JmeterWorkloadRequest request, Path jmxPath, Path jtlPath, Path logPath, Duration timeout)
            throws IOException, InterruptedException {
        List<String> command = command(request.executable(), jmxPath, jtlPath, logPath);
        return start(command, request.runDirectory(), logPath, timeout, request.environment());
    }

    private static int start(List<String> command, Path runDirectory, Path logPath, Duration timeout,
            Map<String, String> environment) throws IOException, InterruptedException {
        ProcessBuilder process = new ProcessBuilder(command);
        process.directory(runDirectory.toFile());
        process.environment().putAll(environment);
        process.redirectErrorStream(true);
        process.redirectOutput(logPath.toFile());
        Map<Long, ProcessHandle> owned = new java.util.LinkedHashMap<>();
        Process started = process.start();
        owned.put(started.pid(), started.toHandle());
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                trackDescendants(owned);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return -1;
                }
                if (started.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(5)), TimeUnit.NANOSECONDS)) {
                    return started.exitValue();
                }
            }
        } finally {
            stopTree(owned);
        }
    }

    private static void trackDescendants(Map<Long, ProcessHandle> owned) {
        List<ProcessHandle> pending = new java.util.ArrayList<>(owned.values());
        for (int index = 0; index < pending.size(); index++) {
            // Expand new handles in this snapshot too, retaining each ancestor even after it exits.
            // Direct-child snapshots avoid reopening every system process for every tracked handle.
            pending.get(index).children().forEach(child -> {
                if (owned.putIfAbsent(child.pid(), child) == null) {
                    pending.add(child);
                }
            });
        }
    }

    private static void stopTree(Map<Long, ProcessHandle> owned) throws IOException {
        trackDescendants(owned);
        List<ProcessHandle> children = List.copyOf(owned.values());
        boolean interrupted = Thread.interrupted();
        try {
            children.reversed().forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (children.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new IOException("performance_jmeter_cleanup_failed");
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<String> arguments(String executable, Path jmxPath, Path jtlPath, Path logPath) {
        return List.of(executable, "-n", "-t", jmxPath.toString(), "-l", jtlPath.toString(), "-j", logPath.toString(),
                "-Jjmeter.save.saveservice.output_format=csv", "-Jjmeter.save.saveservice.print_field_names=true",
                "-Jjmeter.save.saveservice.time=true", "-Jjmeter.save.saveservice.success=true",
                "-Jjmeter.save.saveservice.url=false");
    }

    private static List<String> command(String executable, Path jmxPath, Path jtlPath, Path logPath) {
        List<String> arguments = arguments(executable, jmxPath, jtlPath, logPath);
        String lowerCaseExecutable = executable.toLowerCase(java.util.Locale.ROOT);
        return lowerCaseExecutable.endsWith(".bat") || lowerCaseExecutable.endsWith(".cmd")
                ? prependWindowsCommand(arguments)
                : arguments;
    }

    private static List<String> prependWindowsCommand(List<String> arguments) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(arguments.size() + 3);
        command.add("cmd.exe");
        command.add("/d");
        command.add("/c");
        command.addAll(arguments);
        return List.copyOf(command);
    }
}
