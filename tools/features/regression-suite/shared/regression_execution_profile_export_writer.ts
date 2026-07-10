import { promises as fs } from "node:fs";
import path from "node:path";

import type {
  ExecutionProfileExportManifest,
  WriteExecutionProfileExportInput,
  WriteExecutionProfileExportResult,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_profile_export.model";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";

function sanitizeExportId(exportId: string): string {
  const normalized = exportId.trim();
  if (!normalized) {
    throw new Error("export_id_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("export_id_invalid");
  }
  return normalized;
}

async function writeJsonFile(filePathAbs: string, payload: Record<string, unknown>): Promise<void> {
  await fs.writeFile(filePathAbs, `${JSON.stringify(payload, null, 2)}\n`, "utf8");
}

export async function writeExecutionProfileExport(
  input: WriteExecutionProfileExportInput,
): Promise<WriteExecutionProfileExportResult> {
  const safeExportId = sanitizeExportId(input.exportId);

  const manifest: ExecutionProfileExportManifest = {
    schemaVersion: "1.0.0",
    exportId: safeExportId,
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

  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
  const projectRootAbs = path.dirname(path.dirname(plansRootAbs));
  const exportDirAbs = path.join(projectRootAbs, "exports");
  await fs.mkdir(exportDirAbs, { recursive: true });
  const summaryPathAbs = path.join(exportDirAbs, `${safeExportId}.execution-profile.summary.json`);
  await writeJsonFile(summaryPathAbs, manifest as unknown as Record<string, unknown>);

  return {
    exportId: safeExportId,
    manifest,
  };
}
