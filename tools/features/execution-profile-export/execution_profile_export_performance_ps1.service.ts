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
import { buildReadmeTemplateModel } from "@tools-export-execution-profile/renderers/readme.renderer";
import { preparePs1ExportPackage } from "@tools-export-execution-profile/sections/ps1/export_package.section";
import { buildPs1HealthcheckSection } from "@tools-export-execution-profile/sections/ps1/healthcheck.section";
import { buildPs1RuntimeStartupSection } from "@tools-export-execution-profile/sections/ps1/runtime_startup.section";
import { resolveOneOffExportDir } from "@tools-export-execution-profile/sections/shared/oneoff_export_dir.util";

type PerformanceBundlePlan = {
  order: number;
  planName: string;
  providedContext?: Record<string, unknown>;
  probeBaseUrl?: string;
  contract: Awaited<ReturnType<typeof loadPerformancePlanContract>> extends infer T ? Exclude<T, null> : never;
};

function joinLines(lines: string[]): string {
  return lines.join("\n");
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
  workspaceRootAbs: string;
  target: Awaited<ReturnType<typeof loadExecutionProfileExportTarget>>;
}): Promise<PerformanceBundlePlan[]> {
  const plansRootAbs = path.join(input.target.projectRootAbs, "plans", "performance");
  const out: PerformanceBundlePlan[] = [];
  for (const plan of input.target.profile.plans) {
    const contract = await loadPerformancePlanContract({
      plansRootAbs,
      planName: plan.planName,
    });
    if (!contract) {
      throw new Error(`performance_export_plan_invalid:${plan.planName}`);
    }
    if (contract.workloadProvider.type === "jmeter") {
      throw new Error(`performance_export_workload_provider_unsupported:${plan.planName}:jmeter`);
    }
    const probeBaseUrl =
      contract.observationTargets.baseUrl ??
      (await resolveProbeBaseUrlForExport({
        workspaceRootAbs: input.workspaceRootAbs,
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

export async function exportExecutionProfilePerformancePs1(
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

  const bundlePlans = await buildBundlePlans({
    workspaceRootAbs: input.workspaceRootAbs,
    target,
  });
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

  const runtimeStartupSection = buildPs1RuntimeStartupSection({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
    runtimeContextName: target.profile.runtimeContextName,
    includeRuntimeStartup: defaults.includeRuntimeStartup,
  });
  const healthcheckGateSection = buildPs1HealthcheckSection({
    workspace,
    includeHealthcheckGate: defaults.includeHealthcheckGate,
  });
  const exportPackageSections = await preparePs1ExportPackage({
    workspaceRootAbs: input.workspaceRootAbs,
    exportDirAbs,
    workspace,
    executionProfile: target.profile.executionProfile,
    includeResolvedSecrets,
  });

  await fs.writeFile(
    path.join(exportDirAbs, "performance-export.bundle.json"),
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
    templateFileName: "run-performance-profile.ps1.eta",
    data: {
      manifest,
      includeResolvedSecrets,
      preRuntimeScriptSection: joinLines(exportPackageSections.preRuntimeScriptSection),
      runtimeStartupSection: joinLines(runtimeStartupSection),
      postRuntimeScriptSection: joinLines(exportPackageSections.postRuntimeScriptSection),
      healthcheckGateSection: joinLines(healthcheckGateSection),
      postHealthcheckScriptSection: joinLines(exportPackageSections.postHealthcheckScriptSection),
      prePlanScriptSection: joinLines(exportPackageSections.prePlanScriptSection),
    },
  });
  const readmeText = await renderEtaTemplate({
    templateFileName: "README.execution-profile-export.md.eta",
    data: buildReadmeTemplateModel({ manifest, defaults, includeResolvedSecrets }),
  });

  const scriptPathAbs = path.join(exportDirAbs, "run-performance-profile.ps1");
  const readmePathAbs = path.join(exportDirAbs, "README.performance.ps1.md");
  await fs.writeFile(scriptPathAbs, scriptText, "utf8");
  await fs.writeFile(readmePathAbs, readmeText, "utf8");

  return {
    exportId: manifest.exportId,
    exportDirAbs,
    scriptPathAbs,
    readmePathAbs,
  };
}
