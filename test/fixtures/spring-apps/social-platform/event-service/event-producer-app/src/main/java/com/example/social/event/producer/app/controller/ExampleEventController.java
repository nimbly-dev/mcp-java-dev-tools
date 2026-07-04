package com.example.social.event.producer.app.controller;

import com.example.social.event.api.EventApi;
import com.example.social.event.api.model.EventStatusResponse;
import com.example.social.event.api.model.TriggerIndexRequest;
import com.example.social.event.api.model.TriggerIndexResponse;
import com.example.social.event.producer.app.service.EventTriggerService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExampleEventController implements EventApi {
  private final EventTriggerService triggerService;

  public ExampleEventController(EventTriggerService triggerService) {
    this.triggerService = triggerService;
  }

  @Override
  public TriggerIndexResponse triggerIndex(
      TriggerIndexRequest request, Authentication authentication) {
    return triggerService.triggerIndex(request, authentication.getName());
  }

  @Override
  public EventStatusResponse getEventStatus(String eventId, Authentication authentication) {
    return triggerService.getEventStatus(eventId);
  }
}
