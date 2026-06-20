package com.nimbly.mcpjavadevtools.agent.profiler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProfilerPaths {
  private ProfilerPaths() {}

  public static Path resolveConfiguredOutputDirectory() {
    String fromProp = System.getProperty("mcp.probe.profiler.output.dir");
    if (fromProp != null && !fromProp.trim().isEmpty()) {
      return Path.of(fromProp.trim());
    }
    String fromEnv = System.getenv("MCP_PROBE_PROFILER_OUTPUT_DIR");
    if (fromEnv != null && !fromEnv.trim().isEmpty()) {
      return Path.of(fromEnv.trim());
    }
    String tmpDir = System.getProperty("java.io.tmpdir", ".");
    return Path.of(tmpDir, "mcp-java-dev-tools", "profiler");
  }

  static Path ensureOutputDirectory(Path path) throws IOException {
    Path target = path == null ? resolveConfiguredOutputDirectory() : path;
    Path normalized = target.toAbsolutePath().normalize();
    Files.createDirectories(normalized);
    return normalized;
  }
}
