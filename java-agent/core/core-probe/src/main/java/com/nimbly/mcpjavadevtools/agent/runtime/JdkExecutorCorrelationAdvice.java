package com.nimbly.mcpjavadevtools.agent.runtime;

import java.util.concurrent.Callable;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/** Captures correlation only at the published standard JDK executor boundaries. */
public final class JdkExecutorCorrelationAdvice {
  private JdkExecutorCorrelationAdvice() {}

  @Advice.OnMethodEnter
  public static void enter(
      @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object task) {
    if (CorrelationContext.current() == null || task == null) {
      return;
    }
    if (task instanceof Runnable runnable) {
      task = CorrelationContext.wrap(runnable);
      return;
    }
    if (task instanceof Callable<?> callable) {
      task = CorrelationContext.wrap(callable);
    }
  }
}
