package com.example.social.event.consumer.app.controller;

import com.example.social.event.api.model.TriggerIndexRequest;
import com.example.social.event.consumer.app.model.EventProcessingStatusResponse;
import com.example.social.event.consumer.app.model.IndexRequestedEvent;
import com.example.social.event.consumer.app.service.EventProcessingStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/events")
public class ExampleConsumerController {
  private static final String NOTES_DELAY_PREFIX = "fixture-listener-delay-ms:";
  private final ApplicationEventPublisher eventPublisher;
  private final EventProcessingStore processingStore;

  public ExampleConsumerController(
      ApplicationEventPublisher eventPublisher, EventProcessingStore processingStore) {
    this.eventPublisher = eventPublisher;
    this.processingStore = processingStore;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void acceptEvent(
      @RequestBody TriggerIndexRequest request,
      @RequestHeader("X-Event-Id") String eventId,
      @RequestHeader("X-Accepted-By") String acceptedBy) {
    String tenant = request.data().isEmpty() ? "unknown-tenant" : request.data().get(0);
    int indexedCount = request.data().size();
    processingStore.markAccepted(eventId, tenant, request.type(), acceptedBy, indexedCount);
    eventPublisher.publishEvent(
        new IndexRequestedEvent(
            eventId,
            tenant,
            request.type(),
            acceptedBy,
            indexedCount,
            parseProcessingDelayMs(request.notes())));
  }

  @GetMapping("/{eventId}")
  public EventProcessingStatusResponse getEventStatus(@PathVariable String eventId) {
    return processingStore.require(eventId);
  }

  private long parseProcessingDelayMs(String notes) {
    if (notes == null || !notes.startsWith(NOTES_DELAY_PREFIX)) {
      return 0L;
    }
    String raw = notes.substring(NOTES_DELAY_PREFIX.length()).trim();
    if (raw.isEmpty()) {
      return 0L;
    }
    try {
      long delayMs = Long.parseLong(raw);
      if (delayMs <= 0L || delayMs > 120_000L) {
        return 0L;
      }
      return delayMs;
    } catch (NumberFormatException ignored) {
      return 0L;
    }
  }
}
