package com.nimbly.mcpjavadevtools.agent.instrumentation;

import com.nimbly.mcpjavadevtools.agent.capture.ProbeCaptureStore;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public final class HitAdvice {
  private HitAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static long onEnter(@Advice.Local("threadAllocatedBytesAtEnter") long threadAllocatedBytesAtEnter) {
    threadAllocatedBytesAtEnter = ProbeCaptureStore.currentThreadAllocatedBytes();
    return System.currentTimeMillis();
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void onExit(
      @Advice.Enter long executionStartedAtEpoch,
      @Advice.Local("threadAllocatedBytesAtEnter") long threadAllocatedBytesAtEnter,
      @Advice.Origin("#t") String dottedClassName,
      @Advice.Origin("#m") String methodName,
      @Advice.AllArguments Object[] allArguments,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
      @Advice.Thrown Throwable thrown
  ) {
    ProbeCaptureStore.captureByClassMethod(
        dottedClassName,
        methodName,
        allArguments,
        returnValue,
        thrown,
        executionStartedAtEpoch,
        System.currentTimeMillis(),
        threadAllocatedBytesAtEnter
    );
  }
}

