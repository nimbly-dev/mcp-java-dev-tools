package com.nimbly.mcpjavadevtools.agent.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class LifecycleReportWriter {
  private LifecycleReportWriter() {
  }

  static void write(Path reportFile, ProbeLifecycle.LifecycleResult result) {
    if (reportFile == null) {
      return;
    }
    try {
      Files.writeString(reportFile, serialize(result), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      System.err.println("[probe-agent] Lifecycle report unavailable: " + exception.getMessage());
    }
  }

  private static String serialize(ProbeLifecycle.LifecycleResult result) {
    List<String> classes = result.nonRestorableClasses();
    return "outcome=" + result.outcome() + "\n"
        + "reasonCode=" + result.reasonCode() + "\n"
        + "nonRestorableClassCount=" + classes.size() + "\n"
        + "nonRestorableClasses=" + String.join(",", classes) + "\n";
  }
}
