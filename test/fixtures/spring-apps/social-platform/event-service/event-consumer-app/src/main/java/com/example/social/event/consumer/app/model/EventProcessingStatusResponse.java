package com.example.social.event.consumer.app.model;

public record EventProcessingStatusResponse(
    String eventId,
    String status,
    String acceptedBy,
    String tenant,
    String eventType,
    int indexedCount) {}
