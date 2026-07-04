package com.example.social.event.api.model;

public record TriggerIndexResponse(
    String eventId,
    String status,
    String acceptedBy,
    String route) {}
