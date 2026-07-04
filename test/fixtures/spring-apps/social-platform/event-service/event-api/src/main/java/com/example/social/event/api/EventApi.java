package com.example.social.event.api;

import com.example.social.event.api.model.EventStatusResponse;
import com.example.social.event.api.model.TriggerIndexRequest;
import com.example.social.event.api.model.TriggerIndexResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

@RequestMapping("/api/v1/events")
public interface EventApi {
  @PostMapping("/trigger")
  @ResponseStatus(HttpStatus.OK)
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Trigger an async index event")
  TriggerIndexResponse triggerIndex(
      @Valid @RequestBody TriggerIndexRequest request, Authentication authentication);

  @GetMapping("/{eventId}")
  @PreAuthorize("isAuthenticated()")
  @Operation(summary = "Read processed event state")
  EventStatusResponse getEventStatus(@PathVariable String eventId, Authentication authentication);
}
