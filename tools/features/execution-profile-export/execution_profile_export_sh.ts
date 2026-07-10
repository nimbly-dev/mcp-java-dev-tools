import { promises as fs } from "node:fs";
import path from "node:path";

import { loadProjectWorkspace } from "./loaders/project_workspace.loader";
import { loadExecutionProfileExportManifest } from "./loaders/export_manifest.loader";
import type {
  ExportExecutionProfilePs1Input,
  ExportExecutionProfilePs1Result,
  ExportRuntimeDefaults,
} from "./models/execution_profile_export.model";
import { resolveExportDefaults } from "./policy/export_defaults.policy";
import { renderEtaTemplate } from "./renderers/eta.renderer";
import { buildShHealthcheckSection } from "./sections/sh/healthcheck.section";
import { buildShPlanExecutionSection } from "./sections/sh/plan_execution.section";
import { prepareShExportPackage } from "./sections/sh/export_package.section";
import { buildShPrerequisitesSections } from "./sections/sh/prerequisites.section";
import { buildShRuntimeStartupSection } from "./sections/sh/runtime_startup.section";
import { resolveOneOffExportDir } from "./sections/shared/oneoff_export_dir";

export type ExportExecutionProfileShInput = ExportExecutionProfilePs1Input;
export type ExportExecutionProfileShResult = ExportExecutionProfilePs1Result;

function joinLines(lines: string[]): string {
  return lines.join("\n");
}

function resolveDefaults(input: {
  request: ExportExecutionProfileShInput;
  workspace: Record<string, unknown> | undefined;
}): ExportRuntimeDefaults {
  return resolveExportDefaults({ request: input.request, workspace: input.workspace });
}

export async function exportExecutionProfileSh(input: ExportExecutionProfileShInput): Promise<ExportExecutionProfileShResult> {
  const { manifest, projectRootAbs } = await loadExecutionProfileExportManifest({
    workspaceRootAbs: input.workspaceRootAbs,
    exportId: input.exportId,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
  });

  const workspace = await loadProjectWorkspace({
    workspaceRootAbs: input.workspaceRootAbs,
    projectRootAbs,
  });
  const defaults = resolveDefaults({ request: input, workspace });
  const includeResolvedSecrets = defaults.includeResolvedSecrets;

  const exportDirAbs = resolveOneOffExportDir(projectRootAbs, new Date());
  await fs.mkdir(exportDirAbs, { recursive: true });

  const planExecutionSection = await buildShPlanExecutionSection({
    workspaceRootAbs: input.workspaceRootAbs,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
    workspace,
    executionProfile: manifest.executionProfile,
    planRuns: manifest.planRuns,
  });
  const prerequisiteSections = await buildShPrerequisitesSections({
    workspaceRootAbs: input.workspaceRootAbs,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
    workspace,
    executionProfile: manifest.executionProfile,
    planRuns: manifest.planRuns,
    planExecutionSection,
  });
  const runtimeStartupSection = buildShRuntimeStartupSection({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
    runtimeContextName: manifest.runtimeContextName,
    includeRuntimeStartup: defaults.includeRuntimeStartup,
  });
  const healthcheckGateSection = buildShHealthcheckSection({
    workspace,
    includeHealthcheckGate: defaults.includeHealthcheckGate,
  });
  const exportPackageSections = await prepareShExportPackage({
    workspaceRootAbs: input.workspaceRootAbs,
    exportDirAbs,
    workspace,
    executionProfile: manifest.executionProfile,
    includeResolvedSecrets,
  });

  const scriptText = await renderEtaTemplate({
    templateFileName: "run-execution-profile.sh.eta",
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
      planExecutionSection: joinLines(planExecutionSection),
    },
  });

  const scriptPathAbs = path.join(exportDirAbs, "run-execution-profile.sh");
  await fs.writeFile(scriptPathAbs, scriptText, "utf8");

  return {
    exportId: manifest.exportId,
    exportDirAbs,
    scriptPathAbs,
  };
}
