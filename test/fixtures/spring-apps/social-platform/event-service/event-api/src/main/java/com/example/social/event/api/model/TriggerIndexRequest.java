package com.example.social.event.api.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record TriggerIndexRequest(
    @NotBlank String context,
    @NotBlank String type,
    @NotBlank String groupId,
    @NotBlank String source,
    @NotNull @Min(1) Integer dataFormatVersion,
    @NotBlank String dataId,
    @NotEmpty List<@NotBlank String> data,
    @Size(max = 500) String notes) {}
