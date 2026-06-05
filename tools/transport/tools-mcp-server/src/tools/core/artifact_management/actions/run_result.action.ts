import { promises as fs } from "node:fs";
import path from "node:path";
import { resolveRegressionRunDirAbs } from "@tools-regression-execution-plan-spec/regression_results_report.util";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "@/tools/core/artifact_management/actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "@/tools/core/artifact_management/shared/fail_closed.util";
import { readJsonFile } from "@/tools/core/artifact_management/shared/json_io.util";
import { resolveProjectName } from "@/tools/core/artifact_management/shared/project_resolution.util";

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0);
}

export async function handleRunResultArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"run_result">,
): Promise<ArtifactActionResult> {
  const projectName = await resolveProjectName(ctx.workspaceRootAbs, request.input.projectName);

  if (request.action === "list") {
    const planName = request.input.planName?.trim();
    if (!planName) {
      return buildFailClosedArtifactResponse({
        reasonCode: "plan_name_required",
        reason: "planName is required for run_result list",
        reasonMeta: { action: request.action },
      });
    }
    const runsRoot = path.join(ctx.workspaceRootAbs, ".mcpjvm", projectName, "plans", "regression", planName, "runs");
    const runs = await fs.readdir(runsRoot, { withFileTypes: true }).catch(() => []);
    const runIds = runs
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((a, b) => b.localeCompare(a));
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      planName,
      runIds,
    });
  }

  const runDirArgs: { workspaceRootAbs: string; projectName?: string; planName?: string; runId?: string } = {
    workspaceRootAbs: ctx.workspaceRootAbs,
    projectName,
  };
  if (typeof request.input.planName === "string") runDirArgs.planName = request.input.planName;
  if (typeof request.input.runId === "string") runDirArgs.runId = request.input.runId;
  const runDirAbs = await resolveRegressionRunDirAbs(runDirArgs);
  if (!runDirAbs) {
    return buildFailClosedArtifactResponse({
      reasonCode: "run_artifact_missing",
      reason: "run artifact directory not found",
      reasonMeta: { planName: request.input.planName, runId: request.input.runId },
    });
  }
  const executionResult = await readJsonFile(path.join(runDirAbs, "execution.result.json"));
  const evidence = await readJsonFile(path.join(runDirAbs, "evidence.json"));
  const selectors = asStringArray(request.input.query?.select);
  const includeAll = selectors.length === 0;
  if (includeAll) {
    const executionResultRecord =
      typeof executionResult === "object" && executionResult !== null && !Array.isArray(executionResult)
        ? (executionResult as Record<string, unknown>)
        : {};
    const steps = Array.isArray(executionResultRecord.steps) ? executionResultRecord.steps : [];
    const failedSteps = steps.filter((entry) => {
      if (typeof entry !== "object" || entry === null || Array.isArray(entry)) return false;
      const status = (entry as Record<string, unknown>).status;
      return typeof status === "string" && status !== "passed" && status !== "ok" && status !== "skipped_condition_false";
    }).length;
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      runDirAbs,
      summary: {
        runStatus: typeof executionResultRecord.status === "string" ? executionResultRecord.status : "unknown",
        stepCount: steps.length,
        failedStepCount: failedSteps,
      },
    });
  }
  const artifact: Record<string, unknown> = {};
  if (selectors.includes("executionResult")) artifact.executionResult = executionResult;
  if (selectors.includes("evidence")) artifact.evidence = evidence;
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    runDirAbs,
    artifact,
  });
}
