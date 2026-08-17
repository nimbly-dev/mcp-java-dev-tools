package com.nimbly.mcpjavadevtools.server.core.feature.failureanalysis.model.fingerprint;

import java.util.List;

/** Bounded, sanitized stack-frame fact returned by the Sidecar. */
public record FailureFrame(
        String className,
        String methodName,
        String sourceFile,
        Integer lineNumber,
        String ownership,
        String codeSource,
        String methodDescriptor,
        List<String> codeSourceCandidates,
        String resolutionReason) {

    public FailureFrame {
        codeSourceCandidates = codeSourceCandidates == null ? List.of() : List.copyOf(codeSourceCandidates);
    }

    /** @return class-and-method comparison key used by Sidecar verification */
    public String methodKey() {
        if (className == null || methodName == null) {
            return null;
        }
        return className + "#" + methodName;
    }

    /** @return Strict Line Key when the frame has a positive source line */
    public String strictLineKey() {
        if (lineNumber == null || lineNumber <= 0 || methodKey() == null) {
            return null;
        }
        return methodKey() + ":" + lineNumber;
    }
}
