import path from "node:path";
import { randomUUID } from "node:crypto";

import type {
  RuntimeSuiteManifest,
  RuntimeSuitePlanEntry,
  RuntimeSuiteRunResult,
} from "@tools-regression-execution-plan-spec/models/regression_runtime_suite.model";
import { resolveRegressionPlansRootAbs } from "@tools-regression-execution-plan-spec/regression_artifact_paths.util";
import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import { writeRunSessionExport } from "@tools-regression-execution-plan-spec/regression_run_session_export_writer.util";
import {
  executeRegressionPlanWorkflow,
  type ExecuteRegressionPlanWorkflowArgs,
} from "@tools-regression-execution-plan-spec/regression_plan_executor.util";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function validateSuiteManifest(input: unknown):
  | { ok: true; manifest: RuntimeSuiteManifest }
  | { ok: false; reasonCode: string; requiredUserAction: string[] } {
  if (!isRecord(input)) {
    return { ok: false, reasonCode: "runtime_suite_invalid", requiredUserAction: ["Set runtime suite JSON object."] };
  }
  if (typeof input.executionProfile !== "string" || input.executionProfile.trim().length === 0) {
    return { ok: false, reasonCode: "runtime_suite_invalid", requiredUserAction: ["Set non-empty executionProfile."] };
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
        ...(typeof input.runtimeConfig.retryMax === "number" ? { retryMax: input.runtimeConfig.retryMax } : {}),
      }
    : undefined;
  return {
    ok: true,
    manifest: {
      executionProfile: input.executionProfile.trim(),
      ...(typeof input.runtimeContextName === "string" && input.runtimeContextName.trim().length > 0
        ? { runtimeContextName: input.runtimeContextName.trim() }
        : {}),
      executionPolicy: input.executionPolicy,
      ...(runtimeConfig ? { runtimeConfig } : {}),
      plans,
    },
  };
}

async function readSuiteManifest(args: {
  workspaceRootAbs: string;
  executionProfile: string;
}): Promise<{ ok: true; manifest: RuntimeSuiteManifest } | { ok: false; reasonCode: string; requiredUserAction: string[] }> {
  const plansRootAbs = await resolveRegressionPlansRootAbs(args.workspaceRootAbs);
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
      reasonCode: "runtime_suite_missing",
      requiredUserAction: [`Unable to read projects.json: ${projectsFileAbs}`],
    };
  }
  const workspace = parsed.artifact.workspaces.find((entry) => entry.projectRoot === args.workspaceRootAbs);
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
      requiredUserAction: [`Add executionProfiles entry '${args.executionProfile}' to projects.json.`],
    };
  }
  return validateSuiteManifest(match);
}

export type ExecuteRegressionRuntimeSuiteArgs = {
  workspaceRootAbs: string;
  executionProfile: string;
  mcpInvoke: ExecuteRegressionPlanWorkflowArgs["mcpInvoke"];
};

export async function executeRegressionRuntimeSuite(
  args: ExecuteRegressionRuntimeSuiteArgs,
): Promise<RuntimeSuiteRunResult | { status: "blocked"; reasonCode: string; requiredUserAction: string[] }> {
  const sessionStartedAt = new Date();
  const sessionId = randomUUID();
  const suite = await readSuiteManifest({
    workspaceRootAbs: args.workspaceRootAbs,
    executionProfile: args.executionProfile,
  });
  if (!suite.ok) {
    return {
      status: "blocked",
      reasonCode: suite.reasonCode,
      requiredUserAction: suite.requiredUserAction,
    };
  }
  const manifest = suite.manifest;
  const planRuns: RuntimeSuiteRunResult["planRuns"] = [];
  let hasFail = false;
  let hasBlocked = false;
  const orderedPlans = [...manifest.plans].sort((a, b) => a.order - b.order);
  let stop = false;
  for (const plan of orderedPlans) {
    if (stop) {
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "skipped",
      });
      continue;
    }
    const run = await executeRegressionPlanWorkflow({
      workspaceRootAbs: args.workspaceRootAbs,
      planName: plan.planName,
      mcpInvoke: args.mcpInvoke,
      ...(manifest.runtimeConfig ? { runtimeConfigOverride: manifest.runtimeConfig } : {}),
      ...(plan.runtimeContextName || manifest.runtimeContextName
        ? { runtimeContextName: plan.runtimeContextName ?? manifest.runtimeContextName }
        : {}),
      ...(plan.providedContext ? { providedContext: plan.providedContext } : {}),
    });
    if (run.status === "blocked") {
      hasBlocked = true;
      planRuns.push({
        order: plan.order,
        planName: plan.planName,
        status: "blocked",
        blockedReasonCode: run.preflight.reasonCode,
      });
      const effectiveOnFail =
        plan.onFail === "stop" || plan.onFail === "continue"
          ? plan.onFail
          : manifest.executionPolicy === "stop_on_fail"
            ? "stop"
            : "continue";
      if (effectiveOnFail === "stop") stop = true;
      continue;
    }
    planRuns.push({
      order: plan.order,
      planName: plan.planName,
      status: "executed",
      runStatus: run.runStatus,
      runId: run.runId,
    });
    if (run.runStatus === "fail") hasFail = true;
    if (run.runStatus === "blocked") hasBlocked = true;
    const effectiveOnFail =
      plan.onFail === "stop" || plan.onFail === "continue"
        ? plan.onFail
        : manifest.executionPolicy === "stop_on_fail"
          ? "stop"
          : "continue";
    if ((run.runStatus === "fail" || run.runStatus === "blocked") && effectiveOnFail === "stop") {
      stop = true;
    }
  }

  let status: RuntimeSuiteRunResult["status"] = "pass";
  if (manifest.executionPolicy === "continue_on_fail") {
    if (hasBlocked || hasFail) {
      status = "partial_fail";
    }
  } else if (hasBlocked) {
    status = "blocked";
  } else if (hasFail) {
    status = "fail";
  }

  const result: RuntimeSuiteRunResult = {
    executionProfile: manifest.executionProfile,
    executionPolicy: manifest.executionPolicy,
    status,
    planRuns,
  };
  const sessionEndedAt = new Date();
  try {
    const written = await writeRunSessionExport({
      workspaceRootAbs: args.workspaceRootAbs,
      sessionId,
      generatedAt: sessionEndedAt,
      startedAt: sessionStartedAt,
      endedAt: sessionEndedAt,
      executionProfile: manifest.executionProfile,
      executionPolicy: manifest.executionPolicy,
      runStatus: status,
      ...(manifest.runtimeContextName ? { runtimeContextName: manifest.runtimeContextName } : {}),
      ...(manifest.runtimeConfig ? { runtimeConfig: manifest.runtimeConfig } : {}),
      planRuns,
    });
    result.sessionExport = {
      status: "written",
      sessionId: written.sessionId,
      sessionDirAbs: written.sessionDirAbs,
      manifestPathAbs: written.manifestPathAbs,
    };
  } catch (error) {
    const detail = error instanceof Error ? error.message : "session_export_write_failed";
    result.sessionExport = {
      status: "blocked",
      reasonCode: "session_export_write_failed",
      requiredUserAction: [`Verify export destination is writable. detail=${detail}`],
    };
  }
  return result;
}
