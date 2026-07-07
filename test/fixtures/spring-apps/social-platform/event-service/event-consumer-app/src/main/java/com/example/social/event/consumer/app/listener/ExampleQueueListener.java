package com.example.social.event.consumer.app.listener;

import com.example.social.event.consumer.app.model.IndexRequestedEvent;
import com.example.social.event.consumer.app.service.EventProcessingStore;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ExampleQueueListener {
  private final EventProcessingStore processingStore;

  public ExampleQueueListener(EventProcessingStore processingStore) {
    this.processingStore = processingStore;
  }

  @Async("eventConsumerExecutor")
  @EventListener
  public void receiveEvent(IndexRequestedEvent event) {
    sleepIfRequested(event.processingDelayMs());
    processingStore.markProcessed(
        event.eventId(),
        event.tenant(),
        event.type(),
        event.acceptedBy(),
        event.indexedCount());
  }

  private void sleepIfRequested(long delayMs) {
    if (delayMs <= 0L) {
      return;
    }
    try {
      Thread.sleep(delayMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Fixture listener delay interrupted", interrupted);
    }
  }
}
