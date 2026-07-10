import { promises as fs } from "node:fs";
import path from "node:path";

import { readProjectArtifact } from "@tools-feature-artifact-management";
import type { ExecutionProfileSuiteType } from "../../../spec/regression-execution-plan-spec/src/models/regression_execution_profile_export.model";

import { asString, isRecord } from "../common";

type ExportProfilePlan = {
  order: number;
  planName: string;
  onFail?: "inherit" | "stop" | "continue";
  providedContext?: Record<string, unknown>;
};

export type ExportExecutionProfileTarget = {
  projectName: string;
  projectRootAbs: string;
  workspace: Record<string, unknown>;
  exportId: string;
  profile: {
    executionProfile: string;
    suiteType: ExecutionProfileSuiteType;
    executionPolicy: "stop_on_fail" | "continue_on_fail";
    runtimeContextName?: string;
    runtimeConfig?: {
      requestTimeoutMs?: number;
      retryMax?: number;
    };
    scriptRefs?: Array<Record<string, unknown>>;
    plans: ExportProfilePlan[];
  };
};

function validateExportId(exportId: string): string {
  const normalized = exportId.trim();
  if (!normalized) {
    throw new Error("export_selector_missing");
  }
  if (!/^[A-Za-z0-9._-]+$/.test(normalized)) {
    throw new Error("export_id_invalid");
  }
  return normalized;
}

function formatExportId(epochMs: number, profile: string): string {
  const date = new Date(epochMs);
  const pad = (n: number) => String(n).padStart(2, "0");
  const id = `${date.getUTCFullYear()}${pad(date.getUTCMonth() + 1)}${pad(date.getUTCDate())}-${pad(date.getUTCHours())}${pad(date.getUTCMinutes())}${pad(date.getUTCSeconds())}`;
  return `${id}-${profile.replaceAll(/[^A-Za-z0-9._-]/g, "-")}`;
}

function extractExecutionProfileFromExportId(exportId: string): string | undefined {
  const match = exportId.match(/^\d{8}-\d{6}-(.+)$/);
  if (!match || typeof match[1] !== "string") {
    return undefined;
  }
  const profile = match[1].trim();
  return profile.length > 0 ? profile : undefined;
}

async function fileExists(absPath: string): Promise<boolean> {
  try {
    const stat = await fs.stat(absPath);
    return stat.isFile();
  } catch {
    return false;
  }
}

async function resolveProjectName(args: {
  workspaceRootAbs: string;
  projectName?: string;
}): Promise<string> {
  const mcpjvmRootAbs = path.join(args.workspaceRootAbs, ".mcpjvm");
  if (typeof args.projectName === "string" && args.projectName.trim().length > 0) {
    const selected = args.projectName.trim();
    if (!(await fileExists(path.join(mcpjvmRootAbs, selected, "projects.json")))) {
      throw new Error("project_artifact_missing");
    }
    return selected;
  }
  const entries = await fs.readdir(mcpjvmRootAbs, { withFileTypes: true }).catch(() => []);
  const candidates: string[] = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    if (await fileExists(path.join(mcpjvmRootAbs, entry.name, "projects.json"))) {
      candidates.push(entry.name);
    }
  }
  if (candidates.length === 0) throw new Error("project_artifact_missing");
  if (candidates.length > 1) throw new Error("project_artifact_ambiguous");
  const selected = candidates[0];
  if (!selected) throw new Error("project_artifact_missing");
  return selected;
}

function normalizeRuntimeConfig(
  value: unknown,
): { requestTimeoutMs?: number; retryMax?: number } | undefined {
  if (!isRecord(value)) return undefined;
  const runtimeConfig = {
    ...(typeof value.requestTimeoutMs === "number" ? { requestTimeoutMs: value.requestTimeoutMs } : {}),
    ...(typeof value.retryMax === "number" ? { retryMax: value.retryMax } : {}),
  };
  return Object.keys(runtimeConfig).length > 0 ? runtimeConfig : undefined;
}

function normalizeProfile(raw: Record<string, unknown>): ExportExecutionProfileTarget["profile"] | null {
  const executionProfile = asString(raw.executionProfile);
  const executionPolicy = raw.executionPolicy;
  if (!executionProfile || (executionPolicy !== "stop_on_fail" && executionPolicy !== "continue_on_fail")) {
    return null;
  }
  const suiteTypeRaw = asString(raw.suiteType);
  const suiteType: ExecutionProfileSuiteType =
    suiteTypeRaw === "performance" ? "performance" : "regression";
  const rawPlans = Array.isArray(raw.plans) ? raw.plans : [];
  const plans = rawPlans
    .map((entry) => {
      if (!isRecord(entry)) return null;
      const planName = asString(entry.planName);
      if (!planName || typeof entry.order !== "number" || !Number.isInteger(entry.order) || entry.order <= 0) {
        return null;
      }
      return {
        order: entry.order,
        planName,
        ...(entry.onFail === "inherit" || entry.onFail === "stop" || entry.onFail === "continue"
          ? { onFail: entry.onFail }
          : {}),
        ...(isRecord(entry.providedContext) ? { providedContext: entry.providedContext } : {}),
      };
    })
    .filter((entry): entry is ExportProfilePlan => entry !== null)
    .sort((left, right) => left.order - right.order);
  if (plans.length === 0) {
    return null;
  }
  const runtimeContextName = asString(raw.runtimeContextName);
  const runtimeConfig = normalizeRuntimeConfig(raw.runtimeConfig);
  const scriptRefs = Array.isArray(raw.scriptRefs)
    ? raw.scriptRefs.filter((entry): entry is Record<string, unknown> => isRecord(entry))
    : undefined;
  return {
    executionProfile,
    suiteType,
    executionPolicy,
    ...(runtimeContextName ? { runtimeContextName } : {}),
    ...(runtimeConfig ? { runtimeConfig } : {}),
    ...(scriptRefs && scriptRefs.length > 0 ? { scriptRefs } : {}),
    plans,
  };
}

export async function loadExecutionProfileExportTarget(args: {
  workspaceRootAbs: string;
  projectName?: string;
  exportId?: string;
  executionProfile?: string;
  planName?: string;
  when?: string;
}): Promise<ExportExecutionProfileTarget> {
  const projectName = await resolveProjectName({
    workspaceRootAbs: args.workspaceRootAbs,
    ...(typeof args.projectName === "string" ? { projectName: args.projectName } : {}),
  });
  const projectRootAbs = path.join(args.workspaceRootAbs, ".mcpjvm", projectName);
  const projectsFileAbs = path.join(projectRootAbs, "projects.json");
  const artifact = await readProjectArtifact(projectsFileAbs).catch(() => null);
  if (!artifact || !artifact.ok) {
    throw new Error("project_artifact_missing");
  }
  const workspace =
    artifact.artifact.workspaces.find((entry) => path.resolve(entry.projectRoot) === path.resolve(args.workspaceRootAbs)) ??
    artifact.artifact.workspaces[0];
  if (!workspace) {
    throw new Error("execution_profile_not_found");
  }
  const normalizedWorkspace = workspace as Record<string, unknown>;
  const profiles = (Array.isArray(workspace.executionProfiles) ? (workspace.executionProfiles as unknown[]) : [])
    .map((entry) => (isRecord(entry) ? normalizeProfile(entry) : null))
    .filter((entry): entry is ExportExecutionProfileTarget["profile"] => entry !== null);
  if (profiles.length === 0) {
    throw new Error("execution_profile_not_found");
  }

  let selectedProfile: ExportExecutionProfileTarget["profile"] | undefined;
  let exportId: string | undefined;

  if (typeof args.exportId === "string" && args.exportId.trim().length > 0) {
    exportId = validateExportId(args.exportId);
    const requestedProfile = extractExecutionProfileFromExportId(exportId);
    if (!requestedProfile) {
      throw new Error("execution_profile_export_unresolvable");
    }
    selectedProfile = profiles.find((entry) => entry.executionProfile === requestedProfile);
    if (!selectedProfile) {
      throw new Error("execution_profile_export_unresolvable");
    }
  } else if (typeof args.executionProfile === "string" && args.executionProfile.trim().length > 0) {
    const requested = args.executionProfile.trim();
    const matches = profiles.filter((entry) => entry.executionProfile === requested);
    if (matches.length === 0) throw new Error("execution_profile_not_found");
    if (matches.length > 1) throw new Error("execution_profile_ambiguous");
    selectedProfile = matches[0];
  } else if (typeof args.planName === "string" && args.planName.trim().length > 0) {
    const requestedPlan = args.planName.trim();
    const matches = profiles.filter((entry) => entry.plans.some((plan) => plan.planName === requestedPlan));
    if (matches.length === 0) throw new Error("export_selector_no_match");
    if (matches.length > 1) throw new Error("execution_profile_ambiguous");
    selectedProfile = matches[0];
  } else if (profiles.length === 1) {
    selectedProfile = profiles[0];
  } else {
    throw new Error("execution_profile_ambiguous");
  }

  if (!selectedProfile) {
    throw new Error("execution_profile_not_found");
  }

  if (!exportId) {
    const whenEpoch = typeof args.when === "string" && args.when.trim().length > 0 ? Date.parse(args.when.trim()) : NaN;
    exportId = formatExportId(Number.isFinite(whenEpoch) ? whenEpoch : Date.now(), selectedProfile.executionProfile);
  }

  return {
    projectName,
    projectRootAbs,
    workspace: normalizedWorkspace,
    exportId,
    profile: selectedProfile,
  };
}
