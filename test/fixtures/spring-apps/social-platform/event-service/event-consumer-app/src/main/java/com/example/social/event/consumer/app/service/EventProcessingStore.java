package com.example.social.event.consumer.app.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class EventProcessingStore {
  private final Map<String, String> statusByEventId = new ConcurrentHashMap<>();

  public void markAccepted(String eventId) {
    statusByEventId.put(eventId, "accepted");
  }

  public void markProcessed(String eventId) {
    statusByEventId.put(eventId, "processed");
  }

  public String status(String eventId) {
    return statusByEventId.get(eventId);
  }
}
