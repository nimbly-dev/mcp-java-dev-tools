import path from "node:path";
import { promises as fs } from "node:fs";

import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import {
  inferPlanApiBaseUrlFromProbeConfig,
} from "@tools-regression-execution-plan-spec/regression_plan_base_url.util";
import type { ExecutionProfileExportPlanRun } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { renderShPlanExecutionSection } from "@tools-export-execution-profile/renderers/plan.command.sh.renderer";

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

async function resolveProbeConfigBaseUrls(input: {
  workspaceRootAbs: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<Record<string, string>> {
  const planBaseUrls: Record<string, string> = {};
  for (const plan of input.planRuns) {
    const inferred = await inferPlanApiBaseUrlFromProbeConfig({
      workspaceRootAbs: input.workspaceRootAbs,
      planName: plan.planName,
    });
    if (inferred) {
      planBaseUrls[plan.planName] = inferred;
    }
  }
  return planBaseUrls;
}

function resolveProvidedContextBaseUrls(input: {
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
}): Record<string, string> {
  const profiles = Array.isArray(input.workspace?.executionProfiles) ? input.workspace.executionProfiles : [];
  const profile = profiles
    .filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null && !Array.isArray(entry))
    .find((entry) => asString(entry.executionProfile) === input.executionProfile);
  const plans = Array.isArray(profile?.plans) ? profile.plans : [];
  const out: Record<string, string> = {};
  for (const rawPlan of plans) {
    const plan = asRecord(rawPlan);
    if (!plan) continue;
    const planName = asString(plan.planName);
    const providedContext = asRecord(plan.providedContext);
    const apiBaseUrl = asString(providedContext?.apiBaseUrl);
    if (planName && apiBaseUrl) {
      out[planName] = apiBaseUrl;
    }
  }
  return out;
}

export async function resolvePlanBaseUrls(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<Record<string, string>> {
  const providedContextBaseUrls = resolveProvidedContextBaseUrls({
    workspace: input.workspace,
    executionProfile: input.executionProfile,
  });
  const probeConfigBaseUrls = await resolveProbeConfigBaseUrls({
    workspaceRootAbs: input.workspaceRootAbs,
    planRuns: input.planRuns,
  });
  return {
    ...probeConfigBaseUrls,
    ...providedContextBaseUrls,
  };
}

export async function buildShPlanExecutionSection(input: {
  workspaceRootAbs: string;
  projectName?: string;
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<string[]> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs, input.projectName);
  const planBaseUrls = await resolvePlanBaseUrls({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace: input.workspace,
    executionProfile: input.executionProfile,
    planRuns: input.planRuns,
  });
  return await renderShPlanExecutionSection({
    planRuns: input.planRuns,
    plansRootAbs,
    planBaseUrls,
  });
}
