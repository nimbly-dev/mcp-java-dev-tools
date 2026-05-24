import path from "node:path";
import { promises as fs } from "node:fs";

import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
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

function inferComposeServiceNameCandidates(planName: string): string[] {
  const specBase = planName
    .replace(/-regression-spec$/i, "")
    .replace(/-smoke-spec$/i, "");
  const withoutService = specBase.replace(/-service$/i, "");
  return [...new Set([specBase, withoutService])].filter((candidate) => candidate.length > 0);
}

type ProbeRegistryFile = {
  defaultProfile?: string;
  workspaces?: Array<{ root?: string; profile?: string }>;
  profiles?: Record<
    string,
    {
      probes?: Record<
        string,
        {
          runtime?: {
            port?: unknown;
          };
        }
      >;
    }
  >;
};

function stripBom(input: string): string {
  return input.charCodeAt(0) === 0xfeff ? input.slice(1) : input;
}

function resolveProbeProfile(input: {
  registry: ProbeRegistryFile;
  workspaceRootAbs: string;
}): string | undefined {
  const workspace = input.registry.workspaces?.find((entry) => entry.root === input.workspaceRootAbs);
  if (asString(workspace?.profile)) return asString(workspace?.profile);
  return asString(input.registry.defaultProfile) ?? Object.keys(input.registry.profiles ?? {})[0];
}

async function resolveProbeConfigBaseUrls(input: {
  workspaceRootAbs: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<Record<string, string>> {
  const probeConfigPathAbs = path.join(input.workspaceRootAbs, ".mcpjvm", "probe-config.json");
  const raw = await fs.readFile(probeConfigPathAbs, "utf8").catch(() => "");
  if (!raw) return {};
  let registry: ProbeRegistryFile;
  try {
    registry = JSON.parse(stripBom(raw)) as ProbeRegistryFile;
  } catch {
    return {};
  }
  const profileName = resolveProbeProfile({
    registry,
    workspaceRootAbs: input.workspaceRootAbs,
  });
  const probes = profileName ? registry.profiles?.[profileName]?.probes : undefined;
  if (!probes) return {};

  const planBaseUrls: Record<string, string> = {};
  for (const plan of input.planRuns) {
    const probeId = inferComposeServiceNameCandidates(plan.planName).find((candidate) =>
      typeof probes[candidate]?.runtime?.port === "number"
    );
    const port = probeId ? probes[probeId]?.runtime?.port : undefined;
    if (typeof port === "number" && Number.isInteger(port) && port > 0 && port <= 65535) {
      planBaseUrls[plan.planName] = `http://127.0.0.1:${port}`;
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
  workspace: Record<string, unknown> | undefined;
  executionProfile: string;
  planRuns: ExecutionProfileExportPlanRun[];
}): Promise<string[]> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(input.workspaceRootAbs);
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
