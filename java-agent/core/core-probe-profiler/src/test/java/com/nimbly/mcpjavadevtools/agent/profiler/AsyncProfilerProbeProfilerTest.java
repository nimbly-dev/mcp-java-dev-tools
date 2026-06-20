package com.nimbly.mcpjavadevtools.agent.profiler;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncProfilerProbeProfilerTest {

  @Test
  void buildStartCommandIncludesJfrOutputAtStart() throws Exception {
    Method method = AsyncProfilerProbeProfiler.class.getDeclaredMethod(
        "buildStartCommand",
        String.class,
        Long.class,
        Path.class,
        String.class
    );
    method.setAccessible(true);

    String command = (String) method.invoke(
        null,
        "cpu",
        1_000L,
        Path.of("/tmp/profiler-output.jfr"),
        "jfr"
    );

    assertTrue(command.startsWith("start,event=cpu,interval=1000,file="));
    assertTrue(command.endsWith(",jfr"));
  }

  @Test
  void buildStopCommandUsesPlainStop() throws Exception {
    Method method = AsyncProfilerProbeProfiler.class.getDeclaredMethod("buildStopCommand");
    method.setAccessible(true);

    String command = (String) method.invoke(null);

    assertEquals("stop", command);
  }
}
