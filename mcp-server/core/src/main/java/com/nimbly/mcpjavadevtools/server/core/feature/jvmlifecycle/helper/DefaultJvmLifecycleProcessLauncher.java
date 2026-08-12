package com.nimbly.mcpjavadevtools.server.core.feature.jvmlifecycle.helper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Production process launcher with stderr isolation for STDIO protocol safety.
 */
public final class DefaultJvmLifecycleProcessLauncher implements JvmLifecycleProcessLauncher {

    @Override
    public Process start(String javaBinary, Path helperJar, List<String> arguments)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaBinary);
        command.add("-jar");
        command.add(helperJar.toString());
        command.addAll(arguments);
        return new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
