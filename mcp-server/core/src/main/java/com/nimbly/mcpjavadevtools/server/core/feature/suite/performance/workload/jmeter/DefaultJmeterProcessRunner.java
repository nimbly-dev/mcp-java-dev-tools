package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Process-backed JMeter runner with a hard execution timeout. */
public final class DefaultJmeterProcessRunner implements JmeterProcessRunner {

    /** Runs JMeter without routing subprocess output to the MCP protocol channel. */
    @Override
    public int run(String executable, Path runDirectory, Path jmxPath, Path jtlPath, Path logPath, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder process = new ProcessBuilder(command(executable, jmxPath, jtlPath, logPath));
        process.directory(runDirectory.toFile());
        process.redirectErrorStream(true);
        process.redirectOutput(logPath.toFile());
        Process started = process.start();
        if (!started.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            started.destroyForcibly();
            return -1;
        }
        return started.exitValue();
    }

    private static List<String> arguments(String executable, Path jmxPath, Path jtlPath, Path logPath) {
        return List.of(executable, "-n", "-t", jmxPath.toString(), "-l", jtlPath.toString(), "-j", logPath.toString(),
                "-Jjmeter.save.saveservice.output_format=csv", "-Jjmeter.save.saveservice.print_field_names=true",
                "-Jjmeter.save.saveservice.time=true", "-Jjmeter.save.saveservice.success=true");
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
