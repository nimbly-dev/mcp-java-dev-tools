package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.investigation;

import java.util.Objects;

/** Explicit guided/hands-off investigation bounds; the Feature never broadens them. */
public record FailureInvestigationContext(String mode, int attemptLimit, long elapsedTimeLimitMs) {

    public FailureInvestigationContext {
        mode = Objects.requireNonNull(mode, "mode must not be null").trim();
        if (!("guided".equals(mode) || "hands_off".equals(mode))) {
            throw new IllegalArgumentException("mode must be guided or hands_off");
        }
        if (attemptLimit < 1 || attemptLimit > 10) {
            throw new IllegalArgumentException("attemptLimit must be between 1 and 10");
        }
        if (elapsedTimeLimitMs < 1_000 || elapsedTimeLimitMs > 300_000) {
            throw new IllegalArgumentException("elapsedTimeLimitMs must be between 1000 and 300000");
        }
    }
}
