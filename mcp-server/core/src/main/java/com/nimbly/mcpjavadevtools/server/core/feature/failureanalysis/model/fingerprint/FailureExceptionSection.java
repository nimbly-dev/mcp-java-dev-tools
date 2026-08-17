package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint;

import java.util.List;

/** Bounded causal or suppressed exception section from trace analysis. */
public record FailureExceptionSection(
        String exceptionType,
        boolean suppressed,
        boolean elidedFrames,
        List<FailureFrame> frames) {

    public FailureExceptionSection {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }
}
