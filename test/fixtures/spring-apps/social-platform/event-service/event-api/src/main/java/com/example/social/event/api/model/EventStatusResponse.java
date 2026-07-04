package com.example.social.event.api.model;

public record EventStatusResponse(
    String eventId,
    String status,
    String processedBy,
    String tenant,
    String eventType) {}
