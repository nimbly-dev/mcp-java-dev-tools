package com.example.social.event.consumer.app.model;

public record IndexRequestedEvent(
    String eventId,
    String tenant,
    String type,
    String acceptedBy,
    long processingDelayMs) {}
