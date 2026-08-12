package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Replaceable process-launch boundary for helper execution.
 */
@FunctionalInterface
public interface JvmLifecycleProcessLauncher {

    /**
     * Starts one helper process.
     *
     * @param javaBinary executable
     * @param helperJar compatible helper artifact
     * @param arguments helper arguments
     * @return started process
     * @throws IOException when the process cannot be started
     */
    Process start(String javaBinary, Path helperJar, List<String> arguments) throws IOException;
}
