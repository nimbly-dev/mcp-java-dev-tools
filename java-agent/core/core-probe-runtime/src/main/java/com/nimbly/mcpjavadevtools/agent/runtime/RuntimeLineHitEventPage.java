package com.nimbly.mcpjavadevtools.agent.runtime;

import java.util.List;

public record RuntimeLineHitEventPage(
    List<RuntimeLineHitEvent> events,
    long lastDeliveredSequence,
    boolean hasMore
) {}
