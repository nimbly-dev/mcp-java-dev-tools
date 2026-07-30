package com.nimbly.mcpjavadevtools.agent.bootstrap;

import java.nio.file.Path;

final class LifecycleCommand {
  private final boolean deactivate;
  private final Path reportFile;

  private LifecycleCommand(boolean deactivate, Path reportFile) {
    this.deactivate = deactivate;
    this.reportFile = reportFile;
  }

  static LifecycleCommand parse(String agentArgs) {
    String action = value(agentArgs, "action");
    String reportFile = value(agentArgs, "reportFile");
    return new LifecycleCommand("deactivate".equalsIgnoreCase(action), toReportFile(reportFile));
  }

  boolean deactivate() {
    return deactivate;
  }

  Path reportFile() {
    return reportFile;
  }

  private static String value(String agentArgs, String key) {
    if (agentArgs == null || agentArgs.isBlank()) {
      return "";
    }
    for (String part : agentArgs.split(";")) {
      int separator = part.indexOf('=');
      if (separator <= 0) {
        continue;
      }
      if (key.equalsIgnoreCase(part.substring(0, separator).trim())) {
        return part.substring(separator + 1).trim();
      }
    }
    return "";
  }

  private static Path toReportFile(String rawPath) {
    if (rawPath == null || rawPath.isBlank()) {
      return null;
    }
    Path tempDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
    Path candidate = Path.of(rawPath).toAbsolutePath().normalize();
    if (!candidate.startsWith(tempDirectory)
        || !candidate.getFileName().toString().startsWith("mcp-jvm-lifecycle-")) {
      return null;
    }
    return candidate;
  }
}
