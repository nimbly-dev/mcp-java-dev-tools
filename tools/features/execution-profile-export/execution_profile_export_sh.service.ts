import { promises as fs } from "node:fs";
import path from "node:path";

import { loadProjectWorkspace } from "@tools-export-execution-profile/loaders/project_workspace.loader";
import { loadExecutionProfileExportManifest } from "@tools-export-execution-profile/loaders/export_manifest.loader";
import type {
  ExportExecutionProfilePs1Input,
  ExportExecutionProfilePs1Result,
  ExportRuntimeDefaults,
} from "@tools-export-execution-profile/models/execution_profile_export.model";
import { resolveExportDefaults } from "@tools-export-execution-profile/policy/export_defaults.policy";
import { renderEtaTemplate } from "@tools-export-execution-profile/renderers/eta.renderer";
import { buildShHealthcheckSection } from "@tools-export-execution-profile/sections/sh/healthcheck.section";
import { buildShPlanExecutionSection } from "@tools-export-execution-profile/sections/sh/plan_execution.section";
import { prepareShExportPackage } from "@tools-export-execution-profile/sections/sh/export_package.section";
import { buildShPrerequisitesSections } from "@tools-export-execution-profile/sections/sh/prerequisites.section";
import { buildShRuntimeStartupSection } from "@tools-export-execution-profile/sections/sh/runtime_startup.section";
import { resolveOneOffExportDir } from "@tools-export-execution-profile/sections/shared/oneoff_export_dir.util";

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
    workspace,
    executionProfile: manifest.executionProfile,
    planRuns: manifest.planRuns,
  });
  const prerequisiteSections = await buildShPrerequisitesSections({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
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
