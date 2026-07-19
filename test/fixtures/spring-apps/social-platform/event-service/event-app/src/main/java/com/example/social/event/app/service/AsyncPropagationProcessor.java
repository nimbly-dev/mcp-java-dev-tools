package com.example.social.event.app.service;

import com.example.social.event.app.model.IndexRequestedEvent;
import org.springframework.stereotype.Component;

@Component
public class AsyncPropagationProcessor {
  private final EventProcessingStore processingStore;

  public AsyncPropagationProcessor(EventProcessingStore processingStore) {
    this.processingStore = processingStore;
  }

  public void process(IndexRequestedEvent event) {
    processingStore.markProcessed(
        event.eventId(),
        event.tenant(),
        event.type(),
        "async-propagation-processor");
  }
}
