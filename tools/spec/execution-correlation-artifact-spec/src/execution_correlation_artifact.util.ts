import type {
  ExecutionCorrelationArtifactV1,
  ExecutionCorrelationArtifactValidationResult,
} from "./models/execution_correlation_artifact.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isFiniteNonNegativeNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0;
}

function invalid(reason: string): ExecutionCorrelationArtifactValidationResult {
  return {
    ok: false,
    reasonCode: "correlation_artifact_invalid",
    reason,
    nextAction: "regenerate_correlation_artifact",
  };
}

function isReasonCode(value: unknown): boolean {
  return isNonEmptyString(value);
}

export function validateExecutionCorrelationArtifactV1(
  input: unknown,
): ExecutionCorrelationArtifactValidationResult {
  if (!isRecord(input)) return invalid("Artifact must be an object.");
  if (input.schemaVersion !== 1) return invalid("schemaVersion must be 1.");
  if (!isNonEmptyString(input.suite)) return invalid("suite is required.");
  if (!isNonEmptyString(input.kind)) return invalid("kind is required.");
  if (input.status !== "available" && input.status !== "unavailable") {
    return invalid("status must be available or unavailable.");
  }
  if (!isReasonCode(input.reasonCode)) return invalid("reasonCode is required.");

  const run = input.run;
  if (!isRecord(run) || !isNonEmptyString(run.planId) || !isNonEmptyString(run.runId)) {
    return invalid("run.planId and run.runId are required.");
  }

  const workloadIdentity = input.workloadIdentity;
  if (!isRecord(workloadIdentity)) return invalid("workloadIdentity is required.");
  const provider = workloadIdentity.provider;
  const entrypoint = workloadIdentity.entrypoint;
  const loadModel = workloadIdentity.loadModel;
  if (
    !isRecord(provider) ||
    !isNonEmptyString(provider.type) ||
    !isNonEmptyString(provider.mode) ||
    !isRecord(entrypoint) ||
    !isNonEmptyString(entrypoint.protocol) ||
    !isNonEmptyString(entrypoint.method) ||
    !isNonEmptyString(entrypoint.pathTemplate) ||
    !isRecord(loadModel) ||
    loadModel.mode !== "concurrency" ||
    !isPositiveInteger(loadModel.concurrency) ||
    !isNonNegativeInteger(loadModel.rampUpSeconds) ||
    !isPositiveInteger(loadModel.durationSeconds)
  ) {
    return invalid("workloadIdentity does not match the v1 contract.");
  }

  if (!Array.isArray(input.anchors)) return invalid("anchors must be an array.");
  for (const anchor of input.anchors) {
    if (
      !isRecord(anchor) ||
      anchor.source !== "verified_required_line_hit" ||
      !isNonEmptyString(anchor.strictLineKey) ||
      !isNonEmptyString(anchor.resolvedMethodRef) ||
      anchor.lineHit !== "verified_line_hit"
    ) {
      return invalid("anchors contains an invalid dynamic anchor.");
    }
  }

  const evidence = input.evidence;
  if (!isRecord(evidence) || !Array.isArray(evidence.lineHits) || !isRecord(evidence.msta)) {
    return invalid("evidence.lineHits and evidence.msta are required.");
  }
  for (const lineHit of evidence.lineHits) {
    if (
      !isRecord(lineHit) ||
      !isNonEmptyString(lineHit.strictLineKey) ||
      !["verified_line_hit", "required_line_missed", "unknown"].includes(String(lineHit.status))
    ) {
      return invalid("evidence.lineHits contains an invalid entry.");
    }
  }
  if (
    ![
      "available",
      "not_configured",
      "disabled",
      "jfr_missing",
      "jfr_parse_failed",
      "no_anchor_samples",
    ].includes(String(evidence.msta.status))
  ) {
    return invalid("evidence.msta.status is invalid.");
  }

  if (!Array.isArray(input.attributions)) return invalid("attributions must be an array.");
  if (
    input.status === "available" &&
    (input.anchors.length === 0 || input.attributions.length === 0)
  ) {
    return invalid("available correlation requires anchors and attributions.");
  }
  if (input.status === "unavailable" && input.attributions.length > 0) {
    return invalid("unavailable correlation must have empty attributions.");
  }
  for (const attribution of input.attributions) {
    if (
      !isRecord(attribution) ||
      !isPositiveInteger(attribution.step) ||
      !isNonEmptyString(attribution.anchorMethod) ||
      !isNonEmptyString(attribution.strictLineKey) ||
      !isNonEmptyString(attribution.methodRef) ||
      (attribution.role !== "anchor" && attribution.role !== "dependency") ||
      !isPositiveInteger(attribution.samples) ||
      !isFiniteNonNegativeNumber(attribution.estimatedPathTimeMs) ||
      !isFiniteNonNegativeNumber(attribution.estimatedPathSharePct) ||
      attribution.correlation !== "correlated_sampled_path"
    ) {
      return invalid("attributions contains an invalid entry.");
    }
  }
  if (
    input.notes !== undefined &&
    (!Array.isArray(input.notes) || input.notes.some((note) => !isNonEmptyString(note)))
  ) {
    return invalid("notes must be a string array.");
  }

  if (input.suite === "performance" && input.kind === "sampled_attribution") {
    const mstaStatus = String(evidence.msta.status);
    const reasonCode = String(input.reasonCode);
    const performanceReasonCodes = [
      "sampled_attribution_available",
      "no_verified_line_hit",
      "msta_not_configured",
      "msta_disabled",
      "msta_jfr_missing",
      "msta_jfr_parse_failed",
      "msta_no_anchor_samples",
    ];
    if (!performanceReasonCodes.includes(reasonCode)) {
      return invalid("performance correlation reasonCode is invalid.");
    }
    const hasMissedLineHit = evidence.lineHits.some(
      (lineHit) => isRecord(lineHit) && lineHit.status !== "verified_line_hit",
    );
    const verifiedLineKeys = new Set(
      evidence.lineHits
        .filter(isRecord)
        .filter((lineHit) => lineHit.status === "verified_line_hit")
        .map((lineHit) => String(lineHit.strictLineKey)),
    );
    const anchorLineKeys = new Set(
      input.anchors.filter(isRecord).map((anchor) => String(anchor.strictLineKey)),
    );

    if (input.status === "available") {
      if (reasonCode !== "sampled_attribution_available") {
        return invalid("available correlation must use sampled_attribution_available.");
      }
      if (mstaStatus !== "available") {
        return invalid("available correlation requires available MSTA evidence.");
      }
      if (evidence.lineHits.length === 0) {
        return invalid("available correlation requires line-hit evidence.");
      }
      if (
        input.anchors.some(
          (anchor) => !isRecord(anchor) || !verifiedLineKeys.has(String(anchor.strictLineKey)),
        )
      ) {
        return invalid("available correlation anchors must have verified line-hit evidence.");
      }
      if (
        input.attributions.some(
          (attribution) =>
            !isRecord(attribution) || !anchorLineKeys.has(String(attribution.strictLineKey)),
        )
      ) {
        return invalid("available correlation attributions must reference verified anchors.");
      }
    } else {
      if (reasonCode === "sampled_attribution_available") {
        return invalid("unavailable correlation cannot use sampled_attribution_available.");
      }
      const expectedMstaStatusByReason: Record<string, string> = {
        msta_not_configured: "not_configured",
        msta_disabled: "disabled",
        msta_jfr_missing: "jfr_missing",
        msta_jfr_parse_failed: "jfr_parse_failed",
        msta_no_anchor_samples: "no_anchor_samples",
      };
      const expectedMstaStatus = expectedMstaStatusByReason[reasonCode];
      if (expectedMstaStatus !== undefined && mstaStatus !== expectedMstaStatus) {
        return invalid(`${reasonCode} requires matching MSTA evidence.`);
      }
      if (reasonCode === "no_verified_line_hit" && !hasMissedLineHit) {
        return invalid("no_verified_line_hit requires missed or unknown line-hit evidence.");
      }
      if (
        reasonCode === "no_verified_line_hit" &&
        (input.anchors.length > 0 || input.attributions.length > 0)
      ) {
        return invalid("no_verified_line_hit requires empty anchors and attributions.");
      }
    }
  }

  return { ok: true, artifact: input as unknown as ExecutionCorrelationArtifactV1 };
}
