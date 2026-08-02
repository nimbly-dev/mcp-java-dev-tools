import { promises as fs } from "node:fs";
import path from "node:path";

import { resolvePlansRootAbs } from "@tools-execution-plan-spec/artifact_paths.util";

export function validateSecurityArtifactSegment(value: string, label: string): string {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "." || trimmed === ".." || /[\\/]/.test(trimmed)) {
    throw new Error(`security_${label}_invalid`);
  }
  return trimmed;
}

export async function resolveSecurityPlansRootAbs(
  workspaceRootAbs: string,
  projectName?: string,
): Promise<string> {
  const safeProjectName =
    typeof projectName === "string"
      ? validateSecurityArtifactSegment(projectName, "project_name")
      : undefined;
  return resolvePlansRootAbs({
    workspaceRootAbs,
    suiteType: "security",
    ...(safeProjectName ? { projectName: safeProjectName } : {}),
  });
}

export async function resolveSecurityPlanRootAbs(args: {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
}): Promise<string> {
  const safePlanName = validateSecurityArtifactSegment(args.planName, "plan_name");
  const plansRootAbs = await resolveSecurityPlansRootAbs(args.workspaceRootAbs, args.projectName);
  return path.join(plansRootAbs, safePlanName);
}

export async function resolveSecurityRunRootAbs(args: {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
  runId: string;
}): Promise<string> {
  const safeRunId = validateSecurityArtifactSegment(args.runId, "run_id");
  const planRootAbs = await resolveSecurityPlanRootAbs(args);
  return path.join(planRootAbs, "runs", safeRunId);
}

export async function ensureSecurityRunRootAbs(args: {
  workspaceRootAbs: string;
  projectName: string;
  planName: string;
  runId: string;
}): Promise<string> {
  const runRootAbs = await resolveSecurityRunRootAbs(args);
  await fs.mkdir(runRootAbs, { recursive: true });
  return runRootAbs;
}
