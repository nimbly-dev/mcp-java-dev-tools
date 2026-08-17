package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.endpoint;

import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureExceptionSection;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFingerprint;
import com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint.FailureFrame;
import java.util.List;

/** Sanitized result of parsing a Sidecar analyze response. */
public record FailureAnalysisEvidence(
        FailureFingerprint fingerprint,
        List<FailureFrame> investigationCandidates,
        FailureFrame dependencyBoundary,
        List<FailureExceptionSection> exceptionSections,
        List<String> reasons) {

    public FailureAnalysisEvidence {
        investigationCandidates = investigationCandidates == null ? List.of() : List.copyOf(investigationCandidates);
        exceptionSections = exceptionSections == null ? List.of() : List.copyOf(exceptionSections);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
