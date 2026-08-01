package com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy;

import com.nimbly.mcpjavadevtools.agent.runtime.api.CorrelationApi;
import net.bytebuddy.asm.Advice;

/** Agent-owned KCL consumer boundary for batch correlation binding. */
public final class KclConsumerAdvice {
  private KclConsumerAdvice() {}

  @Advice.OnMethodEnter
  public static CorrelationApi.KclBindingResult enter(
      @Advice.AllArguments Object[] arguments) {
    CorrelationApi.KclBindingResult result = CorrelationApi.bindFromKclArguments(arguments);
    CorrelationApi.recordKclOutcome(result);
    return result;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter CorrelationApi.KclBindingResult bindingResult) {
    if (bindingResult != null) {
      CorrelationApi.restoreKcl(bindingResult.previous());
    }
  }
}
