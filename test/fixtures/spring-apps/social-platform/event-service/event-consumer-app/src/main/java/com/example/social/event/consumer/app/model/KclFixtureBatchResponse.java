package com.example.social.event.consumer.app.model;

public record KclFixtureBatchResponse(
    int recordCount,
    String firstPartitionKey) {}
