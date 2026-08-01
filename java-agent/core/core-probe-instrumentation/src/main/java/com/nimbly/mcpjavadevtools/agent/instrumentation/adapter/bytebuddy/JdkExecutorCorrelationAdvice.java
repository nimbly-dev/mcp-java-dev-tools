package com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy;

import com.nimbly.mcpjavadevtools.agent.runtime.api.BootstrapCorrelationApi;
import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Captures correlation only at the published standard JDK executor boundaries. */
public final class JdkExecutorCorrelationAdvice {
  private JdkExecutorCorrelationAdvice() {}

  @Advice.OnMethodEnter
  public static void enter(
      @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object task) {
    if (!BootstrapCorrelationApi.hasCorrelation() || task == null) {
      return;
    }
    if (task instanceof Runnable runnable) {
      task = BootstrapCorrelationApi.wrap(runnable);
      return;
    }
    if (task instanceof Callable<?> callable) {
      task = BootstrapCorrelationApi.wrap(callable);
    }
  }
}
