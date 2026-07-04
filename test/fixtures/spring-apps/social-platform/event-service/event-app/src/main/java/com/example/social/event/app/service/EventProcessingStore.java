package com.example.social.event.app.service;

import com.example.social.event.api.model.EventStatusResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EventProcessingStore {
  private final Map<String, EventStatusResponse> statusByEventId = new ConcurrentHashMap<>();

  public void markAccepted(String eventId, String tenant, String eventType, String acceptedBy) {
    statusByEventId.put(
        eventId,
        new EventStatusResponse(eventId, "accepted", acceptedBy, tenant, eventType));
  }

  public void markProcessed(String eventId, String tenant, String eventType, String processedBy) {
    statusByEventId.put(
        eventId,
        new EventStatusResponse(eventId, "processed", processedBy, tenant, eventType));
  }

  public EventStatusResponse require(String eventId) {
    EventStatusResponse status = statusByEventId.get(eventId);
    if (status == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId);
    }
    return status;
  }
}
