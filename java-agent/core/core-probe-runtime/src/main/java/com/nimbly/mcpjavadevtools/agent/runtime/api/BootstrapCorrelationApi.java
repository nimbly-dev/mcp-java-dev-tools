package com.nimbly.mcpjavadevtools.agent.runtime.api;

import com.nimbly.mcpjavadevtools.agent.runtime.CorrelationContext;
import java.util.concurrent.Callable;

/** Bootstrap-visible correlation handoff surface for transformed JDK classes. */
public final class BootstrapCorrelationApi {
  private BootstrapCorrelationApi() {}

  public static boolean hasCorrelation() {
    return CorrelationContext.current() != null;
  }

  public static Runnable wrap(Runnable task) {
    return CorrelationContext.wrap(task);
  }

  public static <T> Callable<T> wrap(Callable<T> task) {
    return CorrelationContext.wrap(task);
  }
}
