package com.example.social.event.consumer.app.listener;

import com.example.social.event.consumer.app.model.IndexRequestedEvent;
import com.example.social.event.consumer.app.service.EventProcessingStore;
import org.springframework.stereotype.Component;

/** Unannotated exact-boundary fixture used to prove contract-driven matching. */
@Component
public class ExactConventionConsumer {
  private final EventProcessingStore processingStore;

  public ExactConventionConsumer(EventProcessingStore processingStore) {
    this.processingStore = processingStore;
  }

  public void receive(IndexRequestedEvent event) {
    processingStore.markProcessed(
        event.eventId(),
        event.tenant(),
        event.type(),
        "exact-convention-receive",
        event.indexedCount());
  }

  public void receive(String acceptedBy, IndexRequestedEvent event) {
    processingStore.markProcessed( // exact overload boundary line
        event.eventId(),
        event.tenant(),
        event.type(),
        "exact-convention-receive-overload:" + acceptedBy,
        event.indexedCount());
  }
}
