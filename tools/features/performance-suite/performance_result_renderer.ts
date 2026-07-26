import { promises as fs } from "node:fs";
import path from "node:path";
import {
  validateExecutionCorrelationArtifactV1,
  type ExecutionCorrelationArtifactV1,
} from "@tools-execution-correlation-artifact-spec";

type PerformanceResultRendered = {
  status: "rendered";
  text: string;
};

type PerformanceResultBlocked = {
  status: "blocked";
  reasonCode: "artifact_files_missing" | "correlation_artifact_invalid";
  missing?: string[];
  nextAction: string;
};

export type PerformanceResultRenderResult = PerformanceResultRendered | PerformanceResultBlocked;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown, fallback = "n/a"): string {
  if (typeof value === "string" && value.trim().length > 0) return value;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return fallback;
}

function asNumber(value: unknown, fallback = "n/a"): string {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : fallback;
}

function formatAttributionTable(artifact: ExecutionCorrelationArtifactV1): string {
  const header =
    "| Step | Anchor Method | Strict Line Key | Method | Role | Samples | Estimated Path Time (ms) | Path Share (%) | Correlation Evidence |";
  const separator = "| --- | --- | --- | --- | --- | ---: | ---: | ---: | --- |";
  const rows = [...artifact.attributions]
    .sort((left, right) => left.step - right.step || left.methodRef.localeCompare(right.methodRef))
    .map(
      (attribution) =>
        `| ${attribution.step} | ${attribution.anchorMethod} | ${attribution.strictLineKey} | ${attribution.methodRef} | ${attribution.role} | ${attribution.samples} | ${attribution.estimatedPathTimeMs} | ${attribution.estimatedPathSharePct} | ${attribution.correlation} |`,
    );
  return [header, separator, ...rows].join("\n");
}

async function readJsonObject(filePath: string): Promise<Record<string, unknown> | null> {
  try {
    const parsed: unknown = JSON.parse(await fs.readFile(filePath, "utf8"));
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

export async function renderPerformanceResultFromArtifacts(args: {
  runDirAbs: string;
}): Promise<PerformanceResultRenderResult> {
  const executionPath = path.join(args.runDirAbs, "execution.result.json");
  const evidencePath = path.join(args.runDirAbs, "evidence.json");
  const correlationPath = path.join(args.runDirAbs, "correlation", "correlation.json");
  const [executionResult, evidence] = await Promise.all([
    readJsonObject(executionPath),
    readJsonObject(evidencePath),
  ]);
  const missing = [
    ...(executionResult ? [] : ["execution.result.json"]),
    ...(evidence ? [] : ["evidence.json"]),
  ];
  if (executionResult === null || evidence === null) {
    return {
      status: "blocked",
      reasonCode: "artifact_files_missing",
      missing,
      nextAction: "Regenerate the performance run before rendering its result.",
    };
  }

  const correlationSummary = isRecord(executionResult.correlation)
    ? executionResult.correlation
    : undefined;
  let correlationArtifact: ExecutionCorrelationArtifactV1 | undefined;
  if (correlationSummary?.enabled === true && correlationSummary.status === "available") {
    const correlationObject = await readJsonObject(correlationPath);
    if (!correlationObject) {
      return {
        status: "blocked",
        reasonCode: "artifact_files_missing",
        missing: ["correlation/correlation.json"],
        nextAction: "Regenerate the performance run to persist the required correlation Artifact.",
      };
    }
    const validated = validateExecutionCorrelationArtifactV1(correlationObject);
    if (!validated.ok) {
      return {
        status: "blocked",
        reasonCode: validated.reasonCode,
        nextAction: "Regenerate the performance run to persist a valid correlation Artifact.",
      };
    }
    correlationArtifact = validated.artifact;
  } else if (correlationSummary?.enabled === true) {
    const correlationObject = await readJsonObject(correlationPath);
    if (correlationObject) {
      const validated = validateExecutionCorrelationArtifactV1(correlationObject);
      if (!validated.ok) {
        return {
          status: "blocked",
          reasonCode: validated.reasonCode,
          nextAction: "Regenerate the performance run to persist a valid correlation Artifact.",
        };
      }
      correlationArtifact = validated.artifact;
    }
  }

  const metrics = isRecord(executionResult.metrics) ? executionResult.metrics : {};
  const msta = isRecord(executionResult.msta) ? executionResult.msta : {};
  let correlationText = "n/a (not_configured)";
  if (correlationArtifact?.status === "available") {
    correlationText = `available (${correlationArtifact.reasonCode})`;
  } else if (correlationArtifact?.status === "unavailable") {
    correlationText = `n/a (${correlationArtifact.reasonCode})`;
  } else if (correlationSummary?.enabled === true) {
    correlationText = `n/a (${asString(correlationSummary.reasonCode, "unavailable")})`;
  }
  const lines = [
    "# Performance Result",
    `Status: ${asString(executionResult.status)}`,
    `Duration (ms): ${asNumber(metrics.durationMs)}`,
    `Error Rate (%): ${asNumber(metrics.errorRatePct)}`,
    `Throughput (requests/s): ${asNumber(metrics.throughputPerSec)}`,
    `P95 Latency (ms): ${asNumber(metrics.p95LatencyMs)}`,
    `Required Line Hits: ${Array.isArray(executionResult.requiredLineHits) ? executionResult.requiredLineHits.length : 0}`,
    `MSTA: ${asString(msta.status, "not_configured")}`,
    `Correlation: ${correlationText}`,
  ];
  if (correlationArtifact?.status === "available") {
    lines.push("", formatAttributionTable(correlationArtifact));
  }
  return { status: "rendered", text: lines.join("\n") };
}
