export type ExecutionCorrelationStatus = "available" | "unavailable";

export type ExecutionCorrelationReasonCode = string;

export type ExecutionCorrelationMstaStatus =
  | "available"
  | "not_configured"
  | "disabled"
  | "jfr_missing"
  | "jfr_parse_failed"
  | "no_anchor_samples";

export type ExecutionCorrelationWorkloadIdentity = {
  provider: {
    type: string;
    mode: string;
  };
  entrypoint: {
    protocol: string;
    method: string;
    pathTemplate: string;
  };
  loadModel: {
    mode: "concurrency";
    concurrency: number;
    rampUpSeconds: number;
    durationSeconds: number;
  };
};

export type ExecutionCorrelationAnchor = {
  source: "verified_required_line_hit";
  strictLineKey: string;
  resolvedMethodRef: string;
  lineHit: "verified_line_hit";
};

export type ExecutionCorrelationLineHitEvidence = {
  strictLineKey: string;
  status: "verified_line_hit" | "required_line_missed" | "unknown";
  reasonCode?: string;
};

export type ExecutionCorrelationMstaEvidence = {
  status: ExecutionCorrelationMstaStatus;
  artifactRef?: string;
  provider?: string;
  event?: string;
  outputFormat?: string;
  sampleCount?: number;
};

export type ExecutionCorrelationEvidence = {
  lineHits: ExecutionCorrelationLineHitEvidence[];
  msta: ExecutionCorrelationMstaEvidence;
};

export type ExecutionCorrelationAttribution = {
  step: number;
  anchorMethod: string;
  strictLineKey: string;
  methodRef: string;
  role: "anchor" | "dependency";
  samples: number;
  estimatedPathTimeMs: number;
  estimatedPathSharePct: number;
  correlation: "correlated_sampled_path";
};

export type ExecutionCorrelationArtifactV1 = {
  schemaVersion: 1;
  suite: string;
  kind: string;
  status: ExecutionCorrelationStatus;
  reasonCode: ExecutionCorrelationReasonCode;
  run: {
    planId: string;
    runId: string;
  };
  workloadIdentity: ExecutionCorrelationWorkloadIdentity;
  anchors: ExecutionCorrelationAnchor[];
  evidence: ExecutionCorrelationEvidence;
  attributions: ExecutionCorrelationAttribution[];
  notes?: string[];
};

export type ExecutionCorrelationArtifactValidationResult =
  | { ok: true; artifact: ExecutionCorrelationArtifactV1 }
  | {
      ok: false;
      reasonCode: "correlation_artifact_invalid";
      reason: string;
      nextAction: "regenerate_correlation_artifact";
    };
