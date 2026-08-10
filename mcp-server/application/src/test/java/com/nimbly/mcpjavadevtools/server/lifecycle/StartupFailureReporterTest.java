package com.nimbly.mcpjavadevtools.server.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StartupFailureReporterTest {

    @Test
    void writesOnlyTheSanitizedStartupFailureReason() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        StartupFailureReporter.report(new PrintStream(output, true, StandardCharsets.UTF_8));

        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEqualTo("mcp_java_dev_tools_startup_failed reasonCode=startup_failed" + System.lineSeparator());
    }
}
