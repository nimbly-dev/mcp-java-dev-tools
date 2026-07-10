import { promises as fs } from "node:fs";
import path from "node:path";

import type { LoadedPlanRunArtifacts } from "../models/transport_export.model";
import type { PlanContract } from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_plan_spec.model";
import type { RegressionRunExecutionResult } from "../../../spec/regression-execution-plan-spec/src/models/regression_run_artifact.model";

async function readJson<T>(filePathAbs: string): Promise<T | null> {
  try {
    const text = await fs.readFile(filePathAbs, "utf8");
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

async function resolveRunId(planRunsRootAbs: string, preferredRunId?: string): Promise<string | null> {
  if (typeof preferredRunId === "string" && preferredRunId.trim().length > 0) {
    const preferred = preferredRunId.trim();
    try {
      const stat = await fs.stat(path.join(planRunsRootAbs, preferred));
      if (stat.isDirectory()) {
        return preferred;
      }
    } catch {
      // fall through to latest-run scan
    }
  }
  let entries: import("node:fs").Dirent[] = [];
  try {
    entries = await fs.readdir(planRunsRootAbs, { withFileTypes: true });
  } catch {
    return null;
  }
  const candidates: Array<{ runId: string; score: number }> = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const runDirAbs = path.join(planRunsRootAbs, entry.name);
    try {
      const stat = await fs.stat(runDirAbs);
      candidates.push({ runId: entry.name, score: stat.mtimeMs });
    } catch {
      // ignore
    }
  }
  if (candidates.length === 0) return null;
  candidates.sort((left, right) => right.score - left.score);
  return candidates[0]?.runId ?? null;
}

export async function loadPlanRunArtifacts(input: {
  plansRootAbs: string;
  planName: string;
  runId?: string;
}): Promise<LoadedPlanRunArtifacts | null> {
  const planRootAbs = path.join(input.plansRootAbs, input.planName);
  const planRunsRootAbs = path.join(planRootAbs, "runs");
  const resolvedRunId = await resolveRunId(planRunsRootAbs, input.runId);
  if (!resolvedRunId) return null;

  const runDirAbs = path.join(planRunsRootAbs, resolvedRunId);
  const contract = await readJson<PlanContract>(path.join(planRootAbs, "contract.json"));
  const contextResolved = await readJson<Record<string, unknown>>(path.join(runDirAbs, "context.resolved.json"));
  const executionResult = await readJson<RegressionRunExecutionResult>(path.join(runDirAbs, "execution.result.json"));
  if (!contract || !contextResolved || !executionResult || !Array.isArray(contract.steps)) {
    return null;
  }
  return { contract, contextResolved, executionResult };
}
