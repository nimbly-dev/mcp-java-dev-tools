import { promises as fs } from "node:fs";
import path from "node:path";
import type {
  ExecutionCorrelationArtifactV1,
  ExecutionCorrelationAttribution,
  ExecutionCorrelationMstaStatus,
} from "@tools-execution-correlation-artifact-spec";
import {
  resolveExecutionCorrelationArtifactPath,
  validateExecutionCorrelationArtifactV1,
} from "@tools-execution-correlation-artifact-spec";
import type {
  PersistedPerformanceMstaSummary,
  PerformancePlanContract,
} from "../models/performance_suite.model";

function normalizeLineKeyToAnchorMethod(lineKey: string): string {
  const hashIndex = lineKey.indexOf("#");
  const colonIndex = lineKey.lastIndexOf(":");
  if (hashIndex <= 0 || colonIndex <= hashIndex) return lineKey.trim();
  return lineKey.slice(0, colonIndex).trim();
}

function normalizePathTemplate(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length === 0) return "/";
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

function resolveMstaReasonCode(
  status: ExecutionCorrelationMstaStatus,
):
  | "msta_not_configured"
  | "msta_disabled"
  | "msta_jfr_missing"
  | "msta_jfr_parse_failed"
  | "msta_no_anchor_samples" {
  if (status === "not_configured") return "msta_not_configured";
  if (status === "disabled") return "msta_disabled";
  if (status === "jfr_missing") return "msta_jfr_missing";
  if (status === "jfr_parse_failed") return "msta_jfr_parse_failed";
  return "msta_no_anchor_samples";
}

function buildMstaEvidence(
  msta: PersistedPerformanceMstaSummary,
): ExecutionCorrelationArtifactV1["evidence"]["msta"] {
  if (msta.status === "available") {
    const sampleCount = msta.targets.reduce(
      (total, target) => total + target.anchoredSampleCount,
      0,
    );
    return {
      status: "available",
      artifactRef: "execution-timing.msta.json",
      ...(msta.provider?.name ? { provider: msta.provider.name } : {}),
      ...(msta.provider?.event ? { event: msta.provider.event } : {}),
      ...(msta.provider?.outputFormat ? { outputFormat: msta.provider.outputFormat } : {}),
      sampleCount,
    };
  }
  return {
    status: msta.status,
    ...(msta.status !== "not_configured" && msta.status !== "disabled"
      ? { artifactRef: "execution-timing.msta.json" }
      : {}),
  };
}

function buildAttributions(args: {
  msta: Extract<PersistedPerformanceMstaSummary, { status: "available" }>;
  lineKeys: string[];
}): ExecutionCorrelationAttribution[] {
  const lineKeyByMethod = new Map(
    args.lineKeys.map((lineKey) => [normalizeLineKeyToAnchorMethod(lineKey), lineKey]),
  );
  const attributions: ExecutionCorrelationAttribution[] = [];
  for (const target of args.msta.targets) {
    const strictLineKey = target.strictLineKey || lineKeyByMethod.get(target.anchorMethod);
    if (!strictLineKey) continue;
    for (const step of target.steps) {
      attributions.push({
        step: step.stepOrder,
        anchorMethod: target.anchorMethod,
        strictLineKey,
        methodRef: step.methodRef,
        role: step.target ? "anchor" : "dependency",
        samples: step.samples,
        estimatedPathTimeMs: step.estimatedTimeMs,
        estimatedPathSharePct: step.estimatedTimePct,
        correlation: "correlated_sampled_path",
      });
    }
  }
  return attributions;
}

export function buildPerformanceExecutionCorrelation(args: {
  planId: string;
  runId: string;
  contract: PerformancePlanContract;
  requiredLineHitResults: Array<{ key: string; hit: boolean; reasonCode?: string }>;
  msta: PersistedPerformanceMstaSummary;
  correlationPolicy?: {
    requireLineHit: boolean;
    requireMsta: boolean;
  };
}): ExecutionCorrelationArtifactV1 {
  const entrypoint = args.contract.entrypoints[0]!;
  const lineHits = args.requiredLineHitResults.map((lineHit) => ({
    strictLineKey: lineHit.key,
    status: lineHit.hit ? ("verified_line_hit" as const) : ("required_line_missed" as const),
    ...(lineHit.reasonCode ? { reasonCode: lineHit.reasonCode } : {}),
  }));
  const verifiedLineKeys = lineHits
    .filter((lineHit) => lineHit.status === "verified_line_hit")
    .map((lineHit) => lineHit.strictLineKey);
  const allLineHitsVerified =
    lineHits.length === args.contract.observationTargets.requiredLineHits.length &&
    verifiedLineKeys.length === args.contract.observationTargets.requiredLineHits.length;
  const lineHitPolicySatisfied = args.correlationPolicy?.requireLineHit
    ? allLineHitsVerified
    : verifiedLineKeys.length > 0;
  const anchorLineKeys = args.correlationPolicy?.requireLineHit
    ? args.contract.observationTargets.requiredLineHits
    : verifiedLineKeys;
  const anchors = lineHitPolicySatisfied
    ? anchorLineKeys.map((strictLineKey) => ({
        source: "verified_required_line_hit" as const,
        strictLineKey,
        resolvedMethodRef: normalizeLineKeyToAnchorMethod(strictLineKey),
        lineHit: "verified_line_hit" as const,
      }))
    : [];
  const attributions =
    lineHitPolicySatisfied && args.msta.status === "available"
      ? buildAttributions({
          msta: args.msta,
          lineKeys: anchorLineKeys,
        })
      : [];
  const correlationAvailable =
    lineHitPolicySatisfied && args.msta.status === "available" && attributions.length > 0;
  let reasonCode:
    | "sampled_attribution_available"
    | "no_verified_line_hit"
    | "msta_not_configured"
    | "msta_disabled"
    | "msta_jfr_missing"
    | "msta_jfr_parse_failed"
    | "msta_no_anchor_samples";
  if (!lineHitPolicySatisfied) {
    reasonCode = "no_verified_line_hit";
  } else if (args.msta.status !== "available") {
    reasonCode = resolveMstaReasonCode(args.msta.status);
  } else if (correlationAvailable) {
    reasonCode = "sampled_attribution_available";
  } else {
    reasonCode = "msta_no_anchor_samples";
  }

  return {
    schemaVersion: 1,
    suite: "performance",
    kind: "sampled_attribution",
    status: correlationAvailable ? "available" : "unavailable",
    reasonCode,
    run: {
      planId: args.planId,
      runId: args.runId,
    },
    workloadIdentity: {
      provider: {
        type: args.contract.workloadProvider.type,
        mode:
          "mode" in args.contract.workloadProvider
            ? args.contract.workloadProvider.mode
            : entrypoint.transport.protocol,
      },
      entrypoint: {
        protocol: entrypoint.transport.protocol,
        method: entrypoint.request.method.trim().toUpperCase(),
        pathTemplate: normalizePathTemplate(entrypoint.request.path),
      },
      loadModel: args.contract.loadModel,
    },
    anchors,
    evidence: {
      lineHits,
      msta: buildMstaEvidence(args.msta),
    },
    attributions,
    notes: [
      "Resolved methods and path estimates come from persisted run evidence.",
      "Nested sampled-stack estimates must not be added together.",
      "This Artifact provides attribution, not causal root-cause proof.",
    ],
  };
}

export async function persistPerformanceExecutionCorrelation(args: {
  runDirAbs: string;
  artifact: ExecutionCorrelationArtifactV1;
}): Promise<
  | { ok: true; pathAbs: string }
  | {
      ok: false;
      reasonCode: "correlation_artifact_invalid";
      nextAction: "regenerate_correlation_artifact";
    }
> {
  const validated = validateExecutionCorrelationArtifactV1(args.artifact);
  if (!validated.ok) return validated;
  const pathAbs = resolveExecutionCorrelationArtifactPath({ runDirAbs: args.runDirAbs });
  await fs.mkdir(path.dirname(pathAbs), { recursive: true });
  await fs.writeFile(pathAbs, `${JSON.stringify(validated.artifact, null, 2)}\n`, "utf8");
  return { ok: true, pathAbs };
}
