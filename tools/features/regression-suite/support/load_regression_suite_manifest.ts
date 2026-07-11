/**
 * Regression suite manifest loading and validation.
 *
 * This module owns profile manifest parsing only; runtime state and execution
 * remain in the Regression Suite state support module.
 */
import path from "node:path";
import type {
  RuntimeSuiteManifest,
  RuntimeSuitePlanEntry,
  RuntimeSuiteScriptPhase,
  RuntimeSuiteScriptRef,
} from "../../../spec/regression-execution-plan-spec/src/models/regression_runtime_suite.model";
import { readProjectArtifact } from "@tools-feature-artifact-management";
import { resolveRegressionPlansRootAbs } from "../../../spec/regression-execution-plan-spec/src/regression_artifact_paths.util";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isRuntimeSuiteScriptPhase(value: string): value is RuntimeSuiteScriptPhase {
  return value === "preRuntime" || value === "postRuntime" || value === "postHealthcheck";
}

function isReplayScriptPath(value: string): boolean {
  return /(?:^|[\\/])(?:replay|export)\.(?:ps1|sh|postman\.json)$/i.test(value);
}

function validateSuiteManifest(
  input: unknown,
):
  | { ok: true; manifest: RuntimeSuiteManifest }
  | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  if (!isRecord(input)) {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: ["Set runtime suite JSON object."],
    };
  }
  if (typeof input.executionProfile !== "string" || input.executionProfile.trim().length === 0) {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: ["Set non-empty executionProfile."],
    };
  }
  const suiteType = typeof input.suiteType === "string" ? input.suiteType.trim() : "regression";
  if (suiteType !== "regression") {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: [
        "Set executionProfiles[].suiteType to regression for execution_orchestration.",
      ],
    };
  }
  if (input.executionPolicy !== "stop_on_fail" && input.executionPolicy !== "continue_on_fail") {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: ["Set executionPolicy to stop_on_fail|continue_on_fail."],
    };
  }
  if (!Array.isArray(input.plans) || input.plans.length === 0) {
    return {
      ok: false,
      reasonCode: "runtime_suite_invalid",
      requiredUserAction: ["Set non-empty plans[]."],
    };
  }
  const plans: RuntimeSuitePlanEntry[] = [];
  for (const raw of input.plans) {
    if (!isRecord(raw)) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[] entries as objects."],
      };
    }
    if (typeof raw.order !== "number" || !Number.isInteger(raw.order) || raw.order <= 0) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[].order as positive integer."],
      };
    }
    if (typeof raw.planName !== "string" || raw.planName.trim().length === 0) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set non-empty plans[].planName."],
      };
    }
    if (isReplayScriptPath(raw.planName.trim())) {
      return {
        ok: false,
        reasonCode: "invalid_execution_path_replay_script",
        requiredUserAction: [
          "Use regression plan names only in executionProfiles[].plans[].planName; replay/export script paths are not allowed.",
        ],
      };
    }
    if (
      typeof raw.onFail !== "undefined" &&
      raw.onFail !== "inherit" &&
      raw.onFail !== "stop" &&
      raw.onFail !== "continue"
    ) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[].onFail to inherit|stop|continue."],
      };
    }
    plans.push({
      order: raw.order,
      planName: raw.planName.trim(),
      ...(typeof raw.onFail === "string" ? { onFail: raw.onFail } : {}),
      ...(typeof raw.runtimeContextName === "string" && raw.runtimeContextName.trim().length > 0
        ? { runtimeContextName: raw.runtimeContextName.trim() }
        : {}),
      ...(isRecord(raw.providedContext) ? { providedContext: raw.providedContext } : {}),
    });
  }
  const orders = plans.map((entry) => entry.order).sort((a, b) => a - b);
  for (let i = 0; i < orders.length; i += 1) {
    if (orders[i] !== i + 1) {
      return {
        ok: false,
        reasonCode: "runtime_suite_invalid",
        requiredUserAction: ["Set plans[].order sequentially from 1..N."],
      };
    }
  }
  const runtimeConfig = isRecord(input.runtimeConfig)
    ? {
        ...(typeof input.runtimeConfig.requestTimeoutMs === "number"
          ? { requestTimeoutMs: input.runtimeConfig.requestTimeoutMs }
          : {}),
        ...(typeof input.runtimeConfig.retryMax === "number"
          ? { retryMax: input.runtimeConfig.retryMax }
          : {}),
      }
    : undefined;
  const scriptRefs = Array.isArray(input.scriptRefs)
    ? input.scriptRefs
        .map((entry): RuntimeSuiteScriptRef | null => {
          if (typeof entry === "string" && entry.trim().length > 0) {
            return { name: entry.trim() };
          }
          if (!isRecord(entry)) {
            return null;
          }
          if (typeof entry.name !== "string" || entry.name.trim().length === 0) {
            return null;
          }
          const phase = typeof entry.phase === "string" ? entry.phase.trim() : "";
          const phaseValue = isRuntimeSuiteScriptPhase(phase) ? phase : undefined;
          if (phase.length > 0 && !phaseValue) {
            return null;
          }
          if (phaseValue) {
            return {
              name: entry.name.trim(),
              phase: phaseValue,
            };
          }
          return { name: entry.name.trim() };
        })
        .filter((entry): entry is RuntimeSuiteScriptRef => entry !== null)
    : [];
  return {
    ok: true,
    manifest: {
      executionProfile: input.executionProfile.trim(),
      suiteType: "regression",
      ...(typeof input.runtimeContextName === "string" && input.runtimeContextName.trim().length > 0
        ? { runtimeContextName: input.runtimeContextName.trim() }
        : {}),
      executionPolicy: input.executionPolicy,
      ...(runtimeConfig ? { runtimeConfig } : {}),
      ...(scriptRefs.length > 0 ? { scriptRefs } : {}),
      plans,
    },
  };
}

export async function readSuiteManifest(args: {
  workspaceRootAbs: string;
  projectName?: string;
  executionProfile: string;
}): Promise<
  | { ok: true; manifest: RuntimeSuiteManifest }
  | { ok: false; reasonCode: string; requiredUserAction: string[] }
> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs, args.projectName);
  const projectName = path.basename(path.dirname(path.dirname(plansRootAbs)));
  const projectsFileAbs = path.join(args.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
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
  const match = profiles.find((entry) => entry.executionProfile === args.executionProfile);
  if (!match) {
    return {
      ok: false,
      reasonCode: "runtime_suite_missing",
      requiredUserAction: [
        `Add executionProfiles entry '${args.executionProfile}' to projects.json.`,
      ],
    };
  }
  return validateSuiteManifest(match);
}
