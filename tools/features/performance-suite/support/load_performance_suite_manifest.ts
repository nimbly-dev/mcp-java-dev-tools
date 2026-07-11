/**
 * Performance execution-profile manifest loading.
 *
 * This module owns only profile-to-suite manifest resolution and validation.
 */
import path from "node:path";

import type { RuntimeSuiteManifest } from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import { readProjectArtifact } from "@tools-feature-artifact-management";

export async function readPerformanceSuiteManifest(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
}): Promise<
  | { ok: true; manifest: RuntimeSuiteManifest & { suiteType: "performance" } }
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
    return {
      ok: false,
      reasonCode: parsed.reasonCode,
      requiredUserAction: parsed.errors,
    };
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
  const profiles = Array.isArray(workspace.executionProfiles) ? workspace.executionProfiles : [];
  const profile = profiles.find((entry) => entry.executionProfile === args.executionProfile);
  if (!profile) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: [
        `Add executionProfiles entry '${args.executionProfile}' to projects.json.`,
      ],
    };
  }
  if (profile.suiteType !== "performance") {
    return {
      ok: false,
      reasonCode: "performance_profile_required",
      requiredUserAction: [
        "Set executionProfiles[].suiteType to performance for the selected execution profile.",
      ],
    };
  }
  return {
    ok: true,
    manifest: {
      executionProfile: profile.executionProfile,
      suiteType: "performance",
      ...(profile.runtimeContextName ? { runtimeContextName: profile.runtimeContextName } : {}),
      executionPolicy: profile.executionPolicy,
      ...(profile.runtimeConfig ? { runtimeConfig: profile.runtimeConfig } : {}),
      ...(profile.scriptRefs ? { scriptRefs: profile.scriptRefs } : {}),
      plans: profile.plans,
    },
  };
}
