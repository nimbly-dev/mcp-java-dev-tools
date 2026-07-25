package com.example.social.event.consumer.app.listener;

import com.example.social.event.consumer.app.model.IndexRequestedEvent;
import com.example.social.event.consumer.app.service.EventProcessingStore;
import org.springframework.stereotype.Component;

/** Unannotated legacy-name fixture retained for compatibility coverage. */
@Component
public class LegacyConventionConsumer {
  private final EventProcessingStore processingStore;

  public LegacyConventionConsumer(EventProcessingStore processingStore) {
    this.processingStore = processingStore;
  }

  public void receiveEvent(IndexRequestedEvent event) {
    processingStore.markProcessed(
        event.eventId(),
        event.tenant(),
        event.type(),
        "legacy-convention-receiveEvent",
        event.indexedCount());
  }
}
