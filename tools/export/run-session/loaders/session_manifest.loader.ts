import { promises as fs } from "node:fs";
import path from "node:path";

import type { RunSessionExportManifest, RunSessionExportPlanRun } from "@tools-export-run-session/models/run_session_export.model";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function asString(value: unknown): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  if (!trimmed) {
    return undefined;
  }
  return trimmed;
}

function sanitizeSessionId(sessionId: string): string {
  const normalized = sessionId.trim();
  if (!normalized) {
    throw new Error("session_id_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("session_id_invalid");
  }
  return normalized;
}

function parsePlanRun(entry: unknown): RunSessionExportPlanRun {
  if (!isRecord(entry)) {
    throw new Error("session_manifest_invalid");
  }
  const order = typeof entry.order === "number" ? entry.order : NaN;
  const planName = asString(entry.planName);
  const statusRaw = asString(entry.status);
  if (!Number.isInteger(order) || order <= 0 || !planName || !statusRaw) {
    throw new Error("session_manifest_invalid");
  }

  const status = statusRaw === "blocked" ? "blocked" : statusRaw === "skipped" ? "skipped" : "executed";
  const parsed: RunSessionExportPlanRun = { order, planName, status };

  const runStatusRaw = asString(entry.runStatus);
  if (runStatusRaw === "pass" || runStatusRaw === "fail" || runStatusRaw === "blocked") {
    parsed.runStatus = runStatusRaw;
  }

  const blockedReasonCode = asString(entry.blockedReasonCode);
  if (blockedReasonCode) {
    parsed.blockedReasonCode = blockedReasonCode;
  }

  const runId = asString(entry.runId);
  if (runId) {
    parsed.runId = runId;
  }

  return parsed;
}

export async function loadRunSessionManifest(input: {
  workspaceRootAbs: string;
  sessionId: string;
}): Promise<{ manifest: RunSessionExportManifest; manifestPathAbs: string; sessionDirAbs: string; projectRootAbs: string }> {
  const safeSessionId = sanitizeSessionId(input.sessionId);
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  const sessionDirAbs = path.join(projectRootAbs, "exports", "session-runs-exports", safeSessionId);
  const manifestPathAbs = path.join(sessionDirAbs, "session-manifest.json");

  let raw: unknown;
  try {
    raw = JSON.parse(await fs.readFile(manifestPathAbs, "utf8"));
  } catch {
    throw new Error("session_manifest_missing");
  }

  if (!isRecord(raw)) {
    throw new Error("session_manifest_invalid");
  }
  const schemaVersion = asString(raw.schemaVersion);
  if (schemaVersion !== "1.0.0") {
    throw new Error("session_manifest_invalid");
  }

  const sessionId = asString(raw.sessionId);
  const generatedAt = asString(raw.generatedAt);
  const startedAt = asString(raw.startedAt);
  const endedAt = asString(raw.endedAt);
  const executionProfile = asString(raw.executionProfile);
  const executionPolicyRaw = asString(raw.executionPolicy);
  const runStatusRaw = asString(raw.runStatus);
  if (!sessionId || !generatedAt || !startedAt || !endedAt || !executionProfile || !executionPolicyRaw || !runStatusRaw) {
    throw new Error("session_manifest_invalid");
  }
  if (!Array.isArray(raw.planRuns)) {
    throw new Error("session_manifest_invalid");
  }

  const executionPolicy = executionPolicyRaw === "continue_on_fail" ? "continue_on_fail" : "stop_on_fail";
  let runStatus: "pass" | "fail" | "blocked" | "partial_fail" = "pass";
  if (runStatusRaw === "fail") runStatus = "fail";
  if (runStatusRaw === "blocked") runStatus = "blocked";
  if (runStatusRaw === "partial_fail") runStatus = "partial_fail";

  const manifest: RunSessionExportManifest = {
    schemaVersion: "1.0.0",
    sessionId,
    generatedAt,
    startedAt,
    endedAt,
    executionProfile,
    executionPolicy,
    runStatus,
    planRuns: raw.planRuns.map(parsePlanRun),
  };

  const runtimeContextName = asString(raw.runtimeContextName);
  if (runtimeContextName) {
    manifest.runtimeContextName = runtimeContextName;
  }

  if (isRecord(raw.runtimeConfig)) {
    const runtimeConfig: { requestTimeoutMs?: number; retryMax?: number } = {};
    if (typeof raw.runtimeConfig.requestTimeoutMs === "number") {
      runtimeConfig.requestTimeoutMs = raw.runtimeConfig.requestTimeoutMs;
    }
    if (typeof raw.runtimeConfig.retryMax === "number") {
      runtimeConfig.retryMax = raw.runtimeConfig.retryMax;
    }
    if (Object.keys(runtimeConfig).length > 0) {
      manifest.runtimeConfig = runtimeConfig;
    }
  }

  return { manifest, manifestPathAbs, sessionDirAbs, projectRootAbs };
}
