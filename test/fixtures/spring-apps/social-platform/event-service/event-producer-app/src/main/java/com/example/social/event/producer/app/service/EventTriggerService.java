package com.example.social.event.producer.app.service;

import com.example.social.event.api.model.EventStatusResponse;
import com.example.social.event.api.model.TriggerIndexRequest;
import com.example.social.event.api.model.TriggerIndexResponse;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventTriggerService {
  private static final String NOTES_SLEEP_PREFIX = "fixture-sleep-ms:";
  private final RestClient restClient;
  private final ConcurrentMap<String, EventStatusResponse> acceptedByEventId = new ConcurrentHashMap<>();

  public EventTriggerService(@Value("${fixture.consumer.base-url}") String consumerBaseUrl) {
    this.restClient = RestClient.builder().baseUrl(consumerBaseUrl).build();
  }

  public TriggerIndexResponse triggerIndex(TriggerIndexRequest request, String username) {
    String tenant = request.data().isEmpty() ? "unknown-tenant" : request.data().get(0);
    String eventId = "evt-" + UUID.randomUUID();
    acceptedByEventId.put(eventId, new EventStatusResponse(eventId, "accepted", username, tenant, request.type()));
    restClient
        .post()
        .uri("/internal/events")
        .header("X-Event-Id", eventId)
        .header("X-Accepted-By", username)
        .body(request)
        .retrieve()
        .toBodilessEntity();
    sleepIfRequested(request.notes());
    return new TriggerIndexResponse(
        eventId,
        "accepted",
        username,
        "/api/v1/events/" + eventId);
  }

  private void sleepIfRequested(String notes) {
    if (notes == null || !notes.startsWith(NOTES_SLEEP_PREFIX)) {
      return;
    }
    String raw = notes.substring(NOTES_SLEEP_PREFIX.length()).trim();
    if (raw.isEmpty()) {
      return;
    }
    long sleepMs;
    try {
      sleepMs = Long.parseLong(raw);
    } catch (NumberFormatException ignored) {
      return;
    }
    if (sleepMs <= 0L || sleepMs > 10_000L) {
      return;
    }
    try {
      Thread.sleep(sleepMs);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Fixture sleep interrupted", interrupted);
    }
  }

  public EventStatusResponse getEventStatus(String eventId) {
    EventStatusResponse response = acceptedByEventId.get(eventId);
    if (response == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found: " + eventId);
    }
    return response;
  }
}
