import { promises as fs } from "node:fs";
import path from "node:path";

import { loadExecutionProfileExportTarget } from "@tools-export-execution-profile/loaders/export_target.loader";
import { loadPerformancePlanContract } from "@tools-export-execution-profile/loaders/performance_plan_contract.loader";
import { resolveProbeBaseUrlForExport } from "@tools-export-execution-profile/loaders/probe_export_binding.loader";
import { loadProjectWorkspace } from "@tools-export-execution-profile/loaders/project_workspace.loader";
import type {
  ExportExecutionProfilePs1Input,
  ExportExecutionProfilePs1Result,
  ExportRuntimeDefaults,
  ExecutionProfileExportManifest,
} from "@tools-export-execution-profile/models/execution_profile_export.model";
import { resolveExportDefaults } from "@tools-export-execution-profile/policy/export_defaults.policy";
import { renderEtaTemplate } from "@tools-export-execution-profile/renderers/eta.renderer";
import {
  emitPerformanceExportJmeterArtifacts,
  type PerformanceExportBundlePlan,
} from "@tools-export-execution-profile/performance_jmeter_export.util";
import { assertPerformanceExportProbeBindingsResolved } from "@tools-export-execution-profile/performance_export_probe_binding.util";
import { buildShPrerequisitesSections } from "@tools-export-execution-profile/sections/sh/prerequisites.section";
import { prepareShExportPackage } from "@tools-export-execution-profile/sections/sh/export_package.section";
import { buildShHealthcheckSection } from "@tools-export-execution-profile/sections/sh/healthcheck.section";
import { buildShRuntimeStartupSection } from "@tools-export-execution-profile/sections/sh/runtime_startup.section";
import { resolveOneOffExportDir } from "@tools-export-execution-profile/sections/shared/oneoff_export_dir.util";

function joinLines(lines: string[]): string {
  return lines.join("\n");
}

function collectJmeterArtifactPathsAbs(
  exportDirAbs: string,
  bundlePlans: PerformanceExportBundlePlan[],
): string[] {
  return bundlePlans
    .map((plan) => plan.exportedArtifacts?.jmxPathRel)
    .filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0)
    .map((entry) => path.join(exportDirAbs, entry));
}

function resolveDefaults(input: {
  request: ExportExecutionProfilePs1Input;
  workspace: Record<string, unknown> | undefined;
}): ExportRuntimeDefaults {
  return resolveExportDefaults({ request: input.request, workspace: input.workspace });
}

function buildManifest(input: {
  exportId: string;
  executionProfile: string;
  executionPolicy: "stop_on_fail" | "continue_on_fail";
  runtimeContextName?: string;
  runtimeConfig?: { requestTimeoutMs?: number; retryMax?: number };
  planRuns: Array<{ order: number; planName: string; status: "executed"; runStatus: "blocked" }>;
}): ExecutionProfileExportManifest {
  const nowIso = new Date().toISOString();
  return {
    schemaVersion: "1.0.0",
    exportId: input.exportId,
    generatedAt: nowIso,
    startedAt: nowIso,
    endedAt: nowIso,
    suiteType: "performance",
    executionProfile: input.executionProfile,
    executionPolicy: input.executionPolicy,
    runStatus: "blocked",
    replayPackageType: "workload_replay_only",
    ...(input.runtimeContextName ? { runtimeContextName: input.runtimeContextName } : {}),
    ...(input.runtimeConfig ? { runtimeConfig: input.runtimeConfig } : {}),
    planRuns: input.planRuns,
  };
}

async function buildBundlePlans(input: {
  probeWorkspaceRootAbs: string;
  target: Awaited<ReturnType<typeof loadExecutionProfileExportTarget>>;
}): Promise<PerformanceExportBundlePlan[]> {
  const plansRootAbs = path.join(input.target.projectRootAbs, "plans", "performance");
  const out: PerformanceExportBundlePlan[] = [];
  for (const plan of input.target.profile.plans) {
    const contract = await loadPerformancePlanContract({
      plansRootAbs,
      planName: plan.planName,
    });
    if (!contract) {
      throw new Error(`performance_export_plan_invalid:${plan.planName}`);
    }
    const probeBaseUrl =
      contract.observationTargets.baseUrl ??
      (await resolveProbeBaseUrlForExport({
        workspaceRootAbs: input.probeWorkspaceRootAbs,
        ...(contract.observationTargets.probeId ? { probeId: contract.observationTargets.probeId } : {}),
      }));
    out.push({
      order: plan.order,
      planName: plan.planName,
      ...(plan.providedContext ? { providedContext: plan.providedContext } : {}),
      ...(probeBaseUrl ? { probeBaseUrl } : {}),
      contract,
    });
  }
  return out.sort((left, right) => left.order - right.order);
}

export async function exportExecutionProfilePerformanceSh(
  input: ExportExecutionProfilePs1Input,
): Promise<ExportExecutionProfilePs1Result> {
  const target = await loadExecutionProfileExportTarget({
    workspaceRootAbs: input.workspaceRootAbs,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
    exportId: input.exportId,
  });
  if (target.profile.suiteType !== "performance") {
    throw new Error("performance_profile_required");
  }

  const workspace = await loadProjectWorkspace({
    workspaceRootAbs: input.workspaceRootAbs,
    projectRootAbs: target.projectRootAbs,
  });
  const defaults = resolveDefaults({ request: input, workspace });
  const includeResolvedSecrets = defaults.includeResolvedSecrets;
  const exportDirAbs = resolveOneOffExportDir(target.projectRootAbs, new Date());
  await fs.mkdir(exportDirAbs, { recursive: true });

  const bundlePlans = await emitPerformanceExportJmeterArtifacts({
    exportDirAbs,
    bundlePlans: await buildBundlePlans({
      probeWorkspaceRootAbs:
        typeof target.workspace.projectRoot === "string" && target.workspace.projectRoot.trim().length > 0
          ? target.workspace.projectRoot.trim()
          : input.workspaceRootAbs,
      target,
    }),
  });
  assertPerformanceExportProbeBindingsResolved(bundlePlans);
  const manifest = buildManifest({
    exportId: target.exportId,
    executionProfile: target.profile.executionProfile,
    executionPolicy: target.profile.executionPolicy,
    ...(target.profile.runtimeContextName ? { runtimeContextName: target.profile.runtimeContextName } : {}),
    ...(target.profile.runtimeConfig ? { runtimeConfig: target.profile.runtimeConfig } : {}),
    planRuns: bundlePlans.map((plan) => ({
      order: plan.order,
      planName: plan.planName,
      status: "executed" as const,
      runStatus: "blocked" as const,
    })),
  });

  const runtimeStartupSection = buildShRuntimeStartupSection({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
    runtimeContextName: target.profile.runtimeContextName,
    includeRuntimeStartup: defaults.includeRuntimeStartup,
  });
  const prerequisiteSections = await buildShPrerequisitesSections({
    workspaceRootAbs: input.workspaceRootAbs,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
    workspace,
    executionProfile: target.profile.executionProfile,
    planRuns: target.profile.plans.map((plan) => ({
      order: plan.order,
      planName: plan.planName,
      status: "executed" as const,
      runStatus: "blocked" as const,
    })),
    planExecutionSection: [],
  });
  const healthcheckGateSection = buildShHealthcheckSection({
    workspace,
    includeHealthcheckGate: defaults.includeHealthcheckGate,
  });
  const exportPackageSections = await prepareShExportPackage({
    workspaceRootAbs: input.workspaceRootAbs,
    exportDirAbs,
    workspace,
    executionProfile: target.profile.executionProfile,
    includeResolvedSecrets,
  });
  const jmeterArtifactPathsAbs = collectJmeterArtifactPathsAbs(exportDirAbs, bundlePlans);

  const bundlePathAbs = path.join(exportDirAbs, "performance-export.bundle.json");
  await fs.writeFile(
    bundlePathAbs,
    `${JSON.stringify(
      {
        exportId: manifest.exportId,
        suiteType: "performance",
        executionProfile: manifest.executionProfile,
        executionPolicy: manifest.executionPolicy,
        ...(manifest.runtimeConfig ? { runtimeConfig: manifest.runtimeConfig } : {}),
        plans: bundlePlans,
      },
      null,
      2,
    )}\n`,
    "utf8",
  );

  const runnerText = await renderEtaTemplate({
    templateFileName: "run-performance-profile.js.eta",
    data: {},
  });
  await fs.writeFile(path.join(exportDirAbs, "run-performance-profile.js"), runnerText, "utf8");

  const scriptText = await renderEtaTemplate({
    templateFileName: "run-performance-profile.sh.eta",
    data: {
      manifest,
      includeResolvedSecrets,
      prerequisitesSection: joinLines(prerequisiteSections.prerequisitesSection),
      preRuntimeScriptSection: joinLines(exportPackageSections.preRuntimeScriptSection),
      runtimeStartupSection: joinLines(runtimeStartupSection),
      postRuntimeScriptSection: joinLines(exportPackageSections.postRuntimeScriptSection),
      healthcheckGateSection: joinLines(healthcheckGateSection),
      postHealthcheckScriptSection: joinLines(exportPackageSections.postHealthcheckScriptSection),
      postStartupAuthSection: joinLines(prerequisiteSections.postStartupAuthSection),
      prePlanScriptSection: joinLines(exportPackageSections.prePlanScriptSection),
    },
  });

  const scriptPathAbs = path.join(exportDirAbs, "run-performance-profile.sh");
  await fs.writeFile(scriptPathAbs, scriptText, "utf8");

  return {
    exportId: manifest.exportId,
    exportDirAbs,
    scriptPathAbs,
    ...(jmeterArtifactPathsAbs.length > 0 ? { jmeterArtifactPathsAbs } : {}),
  };
}
