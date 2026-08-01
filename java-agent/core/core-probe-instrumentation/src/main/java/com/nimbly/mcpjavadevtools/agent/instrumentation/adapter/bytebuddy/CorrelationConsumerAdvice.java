package com.nimbly.mcpjavadevtools.agent.instrumentation.adapter.bytebuddy;

import com.nimbly.mcpjavadevtools.agent.runtime.api.CorrelationApi;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

/**
 * Agent-owned consumer boundary. It binds correlation from an event argument
 * for listener/consumer methods and restores the prior thread context.
 */
public final class CorrelationConsumerAdvice {
  private CorrelationConsumerAdvice() {}

  @Advice.OnMethodEnter
  public static CorrelationApi.BindingSnapshot enter(
      @Advice.AllArguments Object[] arguments,
      @Advice.Origin Method origin
  ) {
    return CorrelationApi.bindFromEventArguments(
        arguments, origin, hasKnownConsumerAnnotation(origin));
  }

  public static boolean hasKnownConsumerAnnotation(Method origin) {
    if (origin == null) return false;
    try {
      for (var annotation : origin.getDeclaredAnnotations()) {
        String name = annotation.annotationType().getName();
        if (name.equals("org.springframework.context.event.EventListener")
            || name.equals("org.springframework.kafka.annotation.KafkaListener")
            || name.equals("org.springframework.amqp.rabbit.annotation.RabbitListener")
            || name.equals("org.springframework.jms.annotation.JmsListener")) {
          return true;
        }
      }
    } catch (LinkageError ignored) {
      return false;
    }
    return false;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class)
  public static void exit(@Advice.Enter CorrelationApi.BindingSnapshot previous) {
    CorrelationApi.restore(previous);
  }
}
