package com.example.social.event.consumer.app.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record KclFixtureBatchRequest(
    @NotEmpty List<@Valid KclFixtureRecordRequest> records) {}
