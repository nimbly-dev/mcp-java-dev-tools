package com.nimbly.mcpjavadevtools.server.core.feature.suite.performance.workload.jmeter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/** Intentional boundary for bounded JMeter CLI process execution. */
public interface JmeterProcessRunner {

    /** Runs JMeter and returns its normalized process exit status. */
    int run(String executable, Path runDirectory, Path jmxPath, Path jtlPath, Path logPath, Duration timeout)
            throws IOException, InterruptedException;
}
