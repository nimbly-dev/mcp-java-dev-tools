package com.nimbly.mcpjavadevtools.agent.runtime;

import net.bytebuddy.asm.Advice;

/** Agent-owned KCL consumer boundary for batch correlation binding. */
public final class KclConsumerAdvice {
  private KclConsumerAdvice() {}

  @Advice.OnMethodEnter
  public static CorrelationEventConsumerAdapter.KclBindingResult enter(
      @Advice.AllArguments Object[] arguments) {
    CorrelationEventConsumerAdapter.KclBindingResult result =
        CorrelationEventConsumerAdapter.bindFromKclArguments(arguments);
    ProbeRuntime.recordKclBindingOutcome(result);
    return result;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter CorrelationEventConsumerAdapter.KclBindingResult bindingResult) {
    if (bindingResult != null) {
      CorrelationEventConsumerAdapter.restore(bindingResult.previous());
    }
  }
}
