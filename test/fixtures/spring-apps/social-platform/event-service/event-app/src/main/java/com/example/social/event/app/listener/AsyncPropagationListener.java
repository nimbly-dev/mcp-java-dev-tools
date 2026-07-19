package com.example.social.event.app.listener;

import com.example.social.event.app.model.IndexRequestedEvent;
import com.example.social.event.app.service.AsyncPropagationProcessor;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AsyncPropagationListener {
  private final AsyncPropagationProcessor processor;
  private final ExecutorService executor;

  public AsyncPropagationListener(
      AsyncPropagationProcessor processor,
      @Qualifier("eventCorrelationExecutor") ExecutorService eventCorrelationExecutor) {
    this.processor = processor;
    this.executor = eventCorrelationExecutor;
  }

  @EventListener
  public void receiveForAsyncPropagation(IndexRequestedEvent event) {
    if (!"generic-jdk-correlation".equals(event.notes())) {
      return;
    }
    executor.execute(() -> processor.process(event));
  }
}
