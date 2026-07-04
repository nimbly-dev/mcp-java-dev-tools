package com.example.social.event.app.model;

public record IndexRequestedEvent(
    String eventId,
    String context,
    String type,
    String groupId,
    String source,
    Integer dataFormatVersion,
    String dataId,
    String tenant,
    String notes,
    String acceptedBy) {}
