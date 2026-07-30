package com.nimbly.mcpjavadevtools.agent.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LifecycleCommandTest {
  @Test
  void acceptsTheHelperOwnedTemporaryReportPath() throws Exception {
    Path report = Files.createTempFile("mcp-jvm-lifecycle-", ".report");
    try {
      LifecycleCommand command = LifecycleCommand.parse(
          "action=deactivate;reportFile=" + report.toAbsolutePath());

      assertTrue(command.deactivate());
      assertEquals(report.toAbsolutePath().normalize(), command.reportFile());
    } finally {
      Files.deleteIfExists(report);
    }
  }

  @Test
  void ignoresReportPathsOutsideTheLocalTemporaryDirectory() {
    LifecycleCommand command = LifecycleCommand.parse(
        "action=deactivate;reportFile=C:\\outside\\mcp-jvm-lifecycle-report");

    assertTrue(command.deactivate());
    assertNull(command.reportFile());
  }
}
