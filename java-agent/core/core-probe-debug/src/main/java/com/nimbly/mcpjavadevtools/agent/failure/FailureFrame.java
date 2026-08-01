package com.nimbly.mcpjavadevtools.agent.failure;

/** A bounded stack-frame fact used by Failure Lens. */
public record FailureFrame(
    String className,
    String methodName,
    String sourceFile,
    Integer lineNumber,
    String ownership,
    String codeSource,
    String methodDescriptor,
    java.util.List<String> codeSourceCandidates,
    String resolutionReason
) {
  public FailureFrame {
    codeSourceCandidates = codeSourceCandidates == null ? java.util.List.of() : java.util.List.copyOf(codeSourceCandidates);
  }

  public String methodKey() {
    return className + "#" + methodName;
  }

  public String strictLineKey() {
    if (lineNumber == null || lineNumber <= 0) return null;
    return methodKey() + ":" + lineNumber;
  }
}
