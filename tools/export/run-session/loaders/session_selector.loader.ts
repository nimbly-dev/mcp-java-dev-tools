import { Dirent, promises as fs } from "node:fs";
import path from "node:path";

import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

function asString(value: unknown): string | undefined {
  if (typeof value !== "string") {
    return undefined;
  }
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function validateSessionId(sessionId: string): string {
  const normalized = sessionId.trim();
  if (!normalized) {
    throw new Error("session_selector_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("session_id_invalid");
  }
  return normalized;
}

type SessionCandidate = {
  sessionId: string;
  score: number;
};

async function readExecutionProfileFromLegacyMetadata(sessionDirAbs: string): Promise<string | undefined> {
  const legacyMetadataPathAbs = path.join(sessionDirAbs, "ps1", "export-metadata.json");
  let raw: unknown;
  try {
    raw = JSON.parse(await fs.readFile(legacyMetadataPathAbs, "utf8"));
  } catch {
    return undefined;
  }
  if (!isRecord(raw)) {
    return undefined;
  }
  return asString(raw.executionProfile);
}

export async function resolveSessionIdForExport(input: {
  workspaceRootAbs: string;
  sessionId?: string;
  executionProfile?: string;
}): Promise<string> {
  if (typeof input.sessionId === "string" && input.sessionId.trim().length > 0) {
    return validateSessionId(input.sessionId);
  }

  const executionProfile = asString(input.executionProfile);
  if (!executionProfile) {
    throw new Error("session_selector_missing");
  }

  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  const sessionsRootAbs = path.join(projectRootAbs, "exports", "session-runs-exports");

  let entries: Dirent[] = [];
  try {
    entries = await fs.readdir(sessionsRootAbs, { withFileTypes: true });
  } catch {
    throw new Error("execution_profile_no_sessions");
  }

  const candidates: SessionCandidate[] = [];

  for (const entry of entries) {
    if (!entry.isDirectory()) {
      continue;
    }
    const sessionId = validateSessionId(entry.name);
    const sessionDirAbs = path.join(sessionsRootAbs, sessionId);
    const manifestPathAbs = path.join(sessionDirAbs, "session-manifest.json");

    let profile: string | undefined;
    let endedAt: string | undefined;
    let generatedAt: string | undefined;
    try {
      const raw: unknown = JSON.parse(await fs.readFile(manifestPathAbs, "utf8"));
      if (isRecord(raw)) {
        profile = asString(raw.executionProfile);
        endedAt = asString(raw.endedAt);
        generatedAt = asString(raw.generatedAt);
      }
    } catch {
      profile = await readExecutionProfileFromLegacyMetadata(sessionDirAbs);
    }
    if (profile !== executionProfile) {
      continue;
    }

    const endedScore = endedAt ? Date.parse(endedAt) : Number.NaN;
    const generatedScore = generatedAt ? Date.parse(generatedAt) : Number.NaN;

    let score = Number.isFinite(endedScore) ? endedScore : Number.isFinite(generatedScore) ? generatedScore : Number.NaN;
    if (!Number.isFinite(score)) {
      const stat = await fs.stat(sessionDirAbs);
      score = stat.mtimeMs;
    }

    candidates.push({ sessionId, score });
  }

  if (candidates.length === 0) {
    throw new Error("execution_profile_no_sessions");
  }

  candidates.sort((a, b) => b.score - a.score);
  const winner = candidates[0];
  if (!winner) {
    throw new Error("execution_profile_no_sessions");
  }
  return winner.sessionId;
}
