package com.nimbly.mcpjavadevtools.server.lifecycle;

import java.io.PrintStream;

public class StartupFailureReporter {

    private static final String MESSAGE = "mcp_java_dev_tools_startup_failed reasonCode=startup_failed";

    private StartupFailureReporter() {
    }

    public static void report(PrintStream errorStream) {
        errorStream.println(MESSAGE);
    }
}
