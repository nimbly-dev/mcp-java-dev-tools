import path from "node:path";

import type {
  RuntimeSuitePlanEntry,
} from "@tools-execution-plan-spec/models/runtime_suite.model";
import type {
  ExecutionProfileEntry,
} from "@tools-project-artifact-spec/models/project_artifact.model";
import { readProjectArtifact } from "@tools-feature-artifact-management";

import type { SecuritySuiteManifest } from "../models/security_suite.model";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function validatePlanEntries(value: unknown):
  | { ok: true; plans: RuntimeSuitePlanEntry[] }
  | { ok: false; requiredUserAction: string[] } {
  if (!Array.isArray(value) || value.length === 0) {
    return { ok: false, requiredUserAction: ["Set non-empty security plans[]."] };
  }
  const plans: RuntimeSuitePlanEntry[] = [];
  for (const raw of value) {
    if (!isRecord(raw) || !Number.isInteger(raw.order) || Number(raw.order) <= 0) {
      return { ok: false, requiredUserAction: ["Set security plans[].order as a positive integer."] };
    }
    if (typeof raw.planName !== "string" || raw.planName.trim().length === 0) {
      return { ok: false, requiredUserAction: ["Set non-empty security plans[].planName."] };
    }
    if (
      raw.onFail !== undefined &&
      raw.onFail !== "inherit" &&
      raw.onFail !== "stop" &&
      raw.onFail !== "continue"
    ) {
      return {
        ok: false,
        requiredUserAction: ["Set security plans[].onFail to inherit|stop|continue."],
      };
    }
    plans.push({
      order: Number(raw.order),
      planName: raw.planName.trim(),
      ...(typeof raw.onFail === "string" ? { onFail: raw.onFail } : {}),
    });
  }
  const orders = plans.map((plan) => plan.order).sort((a, b) => a - b);
  if (orders.some((order, index) => order !== index + 1)) {
    return {
      ok: false,
      requiredUserAction: ["Set security plans[].order sequentially from 1..N."],
    };
  }
  return { ok: true, plans };
}

function toManifest(profile: ExecutionProfileEntry, plans: RuntimeSuiteManifestPlan[]): SecuritySuiteManifest {
  return {
    executionProfile: profile.executionProfile,
    suiteType: "security",
    executionPolicy: profile.executionPolicy,
    plans,
  };
}

type RuntimeSuiteManifestPlan = SecuritySuiteManifest["plans"][number];

export async function readSecuritySuiteManifest(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
}): Promise<
  | { ok: true; manifest: SecuritySuiteManifest }
  | { ok: false; reasonCode: string; requiredUserAction: string[] }
> {
  const projectsFileAbs = path.join(
    args.workspaceRootAbs,
    ".mcpjvm",
    args.projectName,
    "projects.json",
  );
  const parsed = await readProjectArtifact(projectsFileAbs).catch(() => ({
    ok: false as const,
    reasonCode: "project_artifact_missing" as const,
    errors: [`Create project artifact at ${projectsFileAbs}.`],
  }));
  if (!parsed.ok) {
    return { ok: false, reasonCode: parsed.reasonCode, requiredUserAction: parsed.errors };
  }
  const workspace = parsed.artifact.workspaces.find(
    (entry) => entry.projectRoot === args.workspaceRootAbs,
  );
  if (!workspace) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: ["Workspace entry not found for current projectRoot in projects.json."],
    };
  }
  const profile = (workspace.executionProfiles ?? []).find(
    (entry) => entry.executionProfile === args.executionProfile,
  );
  if (!profile) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: [
        `Add executionProfiles entry '${args.executionProfile}' to projects.json.`,
      ],
    };
  }
  if (profile.suiteType !== "security") {
    return {
      ok: false,
      reasonCode: "security_profile_required",
      requiredUserAction: [
        "Set executionProfiles[].suiteType to security for the selected execution profile.",
      ],
    };
  }
  const plans = validatePlanEntries(profile.plans);
  if (!plans.ok) return { ok: false, reasonCode: "runtime_suite_invalid", requiredUserAction: plans.requiredUserAction };
  return {
    ok: true,
    manifest: toManifest(profile, plans.plans.map((plan) => ({
      order: plan.order,
      planName: plan.planName,
      ...(plan.onFail ? { onFail: plan.onFail } : {}),
    }))),
  };
}
