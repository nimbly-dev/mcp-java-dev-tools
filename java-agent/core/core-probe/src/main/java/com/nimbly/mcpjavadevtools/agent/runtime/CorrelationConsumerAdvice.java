package com.nimbly.mcpjavadevtools.agent.runtime;

import net.bytebuddy.asm.Advice;

/**
 * Agent-owned consumer boundary. It binds correlation from an event argument
 * for listener/consumer methods and restores the prior thread context.
 */
public final class CorrelationConsumerAdvice {
  private CorrelationConsumerAdvice() {}

  @Advice.OnMethodEnter
  public static CorrelationContext.BindingSnapshot enter(@Advice.AllArguments Object[] arguments) {
    return CorrelationEventConsumerAdapter.bindFromEventArguments(arguments);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void exit(@Advice.Enter CorrelationContext.BindingSnapshot previous) {
    CorrelationEventConsumerAdapter.restore(previous);
  }
}
