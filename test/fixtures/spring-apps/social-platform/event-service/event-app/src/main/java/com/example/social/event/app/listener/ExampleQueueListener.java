package com.example.social.event.app.listener;

import com.example.social.event.app.model.IndexRequestedEvent;
import com.example.social.event.app.service.EventProcessingStore;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ExampleQueueListener {
  private final EventProcessingStore processingStore;

  public ExampleQueueListener(EventProcessingStore processingStore) {
    this.processingStore = processingStore;
  }

  @Async("eventFixtureExecutor")
  @EventListener
  public void receiveEvent(IndexRequestedEvent event) {
    processingStore.markProcessed(
        event.eventId(),
        event.tenant(),
        event.type(),
        "example-queue-listener");
  }
}
