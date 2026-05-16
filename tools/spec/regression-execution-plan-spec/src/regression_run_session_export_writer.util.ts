import { promises as fs } from "node:fs";
import path from "node:path";

import type {
  RunSessionExportManifest,
  WriteRunSessionExportInput,
  WriteRunSessionExportResult,
} from "@tools-regression-execution-plan-spec/models/regression_run_session_export.model";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";

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

async function writeJsonFile(filePathAbs: string, payload: Record<string, unknown>): Promise<void> {
  await fs.writeFile(filePathAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

export async function writeRunSessionExport(
  input: WriteRunSessionExportInput,
): Promise<WriteRunSessionExportResult> {
  const safeSessionId = sanitizeSessionId(input.sessionId);
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  const sessionDirAbs = path.join(projectRootAbs, "exports", "session-runs-exports", safeSessionId);
  await fs.mkdir(sessionDirAbs, { recursive: true });

  const manifest: RunSessionExportManifest = {
    schemaVersion: "1.0.0",
    sessionId: safeSessionId,
    generatedAt: input.generatedAt.toISOString(),
    startedAt: input.startedAt.toISOString(),
    endedAt: input.endedAt.toISOString(),
    executionProfile: input.executionProfile,
    executionPolicy: input.executionPolicy,
    runStatus: input.runStatus,
    ...(typeof input.runtimeContextName === "string" && input.runtimeContextName.trim().length > 0
      ? { runtimeContextName: input.runtimeContextName.trim() }
      : {}),
    ...(input.runtimeConfig ? { runtimeConfig: input.runtimeConfig } : {}),
    planRuns: [...input.planRuns].sort((left, right) => left.order - right.order),
  };

  const manifestPathAbs = path.join(sessionDirAbs, "session-manifest.json");
  await writeJsonFile(manifestPathAbs, manifest as unknown as Record<string, unknown>);

  return {
    sessionId: safeSessionId,
    sessionDirAbs,
    manifestPathAbs,
    manifest,
  };
}
