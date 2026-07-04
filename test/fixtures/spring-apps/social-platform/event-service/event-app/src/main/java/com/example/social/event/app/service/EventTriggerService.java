package com.example.social.event.app.service;

import com.example.social.event.api.model.EventStatusResponse;
import com.example.social.event.api.model.TriggerIndexRequest;
import com.example.social.event.api.model.TriggerIndexResponse;
import com.example.social.event.app.model.IndexRequestedEvent;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class EventTriggerService {
  private final ApplicationEventPublisher eventPublisher;
  private final EventProcessingStore processingStore;

  public EventTriggerService(
      ApplicationEventPublisher eventPublisher, EventProcessingStore processingStore) {
    this.eventPublisher = eventPublisher;
    this.processingStore = processingStore;
  }

  public TriggerIndexResponse triggerIndex(TriggerIndexRequest request, String username) {
    String tenant = request.data().isEmpty() ? "unknown-tenant" : request.data().get(0);
    String eventId = "evt-" + UUID.randomUUID();
    processingStore.markAccepted(eventId, tenant, request.type(), username);
    eventPublisher.publishEvent(
        new IndexRequestedEvent(
            eventId,
            request.context(),
            request.type(),
            request.groupId(),
            request.source(),
            request.dataFormatVersion(),
            request.dataId(),
            tenant,
            request.notes(),
            username));
    return new TriggerIndexResponse(
        eventId,
        "accepted",
        username,
        "/api/v1/events/" + eventId);
  }

  public EventStatusResponse getEventStatus(String eventId) {
    return processingStore.require(eventId);
  }
}
