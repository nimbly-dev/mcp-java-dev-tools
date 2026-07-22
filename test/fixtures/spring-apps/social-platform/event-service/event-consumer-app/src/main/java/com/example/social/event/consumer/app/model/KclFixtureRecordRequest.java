package com.example.social.event.consumer.app.model;

import jakarta.validation.constraints.NotBlank;

public record KclFixtureRecordRequest(
    @NotBlank String eventId,
    @NotBlank String partitionKey) {}
