import { promises as fs } from "node:fs";
import path from "node:path";
import type { RunStateRebuildSummary } from "../model/run_state_store.model";

export type JsonRecord = Record<string, unknown>;
export type RunSource = {
  planName: string;
  runId: string;
  runDirAbs: string;
  runDirPathRel: string;
  execution: JsonRecord;
  evidence: JsonRecord;
  evidencePresent: boolean;
  files: Array<{
    kind: "context_resolved" | "execution_result" | "evidence" | "correlation";
    pathAbs: string;
  }>;
};

const MAX_RUNS = 10_000;
const MAX_REASONS = 100;

function asRecord(value: unknown): JsonRecord | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as JsonRecord)
    : undefined;
}

async function readJsonRecord(filePathAbs: string): Promise<JsonRecord | undefined> {
  try {
    return asRecord(JSON.parse(await fs.readFile(filePathAbs, "utf8")));
  } catch {
    return undefined;
  }
}

function relativeToWorkspace(workspaceRootAbs: string, filePathAbs: string): string {
  return path.relative(workspaceRootAbs, filePathAbs).replaceAll("\\", "/");
}

/** Performs bounded discovery without reading legacy indexes or SQLite state. */
export async function scanRunStateSources(args: {
  workspaceRootAbs: string;
  projectName: string;
  summary: RunStateRebuildSummary;
  reasons: Array<Record<string, unknown>>;
}): Promise<RunSource[]> {
  const root = path.join(args.workspaceRootAbs, ".mcpjvm", args.projectName, "plans", "regression");
  const plans = await fs.readdir(root, { withFileTypes: true }).catch(() => []);
  const sources: RunSource[] = [];
  for (const plan of plans
    .filter((entry) => entry.isDirectory())
    .sort((a, b) => a.name.localeCompare(b.name))) {
    const runsRoot = path.join(root, plan.name, "runs");
    const runs = await fs.readdir(runsRoot, { withFileTypes: true }).catch(() => []);
    for (const run of runs
      .filter((entry) => entry.isDirectory())
      .sort((a, b) => a.name.localeCompare(b.name))) {
      if (sources.length >= MAX_RUNS) throw new Error("state_store_rebuild_source_limit");
      args.summary.scannedRuns += 1;
      const runDirAbs = path.join(runsRoot, run.name);
      const executionPathAbs = path.join(runDirAbs, "execution.result.json");
      const evidencePathAbs = path.join(runDirAbs, "evidence.json");
      const execution = await readJsonRecord(executionPathAbs);
      const evidence = await readJsonRecord(evidencePathAbs);
      if (!execution) {
        args.summary.invalidRuns += 1;
        if (args.reasons.length < MAX_REASONS)
          args.reasons.push({
            planName: plan.name,
            runId: run.name,
            reasonCode: "execution_result_invalid",
          });
        continue;
      }
      const evidencePresent = Boolean(evidence);
      if (!evidencePresent && args.reasons.length < MAX_REASONS)
        args.reasons.push({ planName: plan.name, runId: run.name, reasonCode: "evidence_missing" });
      const files: RunSource["files"] = [{ kind: "execution_result", pathAbs: executionPathAbs }];
      for (const [kind, name] of [
        ["context_resolved", "context.resolved.json"],
        ["evidence", "evidence.json"],
        ["correlation", "correlation/correlation.json"],
      ] as const) {
        const candidate = path.join(runDirAbs, name);
        try {
          if ((await fs.stat(candidate)).isFile()) files.push({ kind, pathAbs: candidate });
        } catch {
          // Optional canonical evidence is reported through target-specific counts.
        }
      }
      sources.push({
        planName: plan.name,
        runId: run.name,
        runDirAbs,
        runDirPathRel: relativeToWorkspace(args.workspaceRootAbs, runDirAbs),
        execution,
        evidence: evidence ?? {},
        evidencePresent,
        files,
      });
    }
  }
  return sources;
}
