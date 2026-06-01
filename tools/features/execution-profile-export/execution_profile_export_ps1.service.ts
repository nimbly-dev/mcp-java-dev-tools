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
import { buildReadmeTemplateModel } from "@tools-export-execution-profile/renderers/readme.renderer";
import { buildPs1HealthcheckSection } from "@tools-export-execution-profile/sections/ps1/healthcheck.section";
import { buildPs1PlanExecutionSection } from "@tools-export-execution-profile/sections/ps1/plan_execution.section";
import { buildPs1PrerequisitesSections } from "@tools-export-execution-profile/sections/ps1/prerequisites.section";
import { preparePs1ExportPackage } from "@tools-export-execution-profile/sections/ps1/export_package.section";
import { buildPs1RuntimeStartupSection } from "@tools-export-execution-profile/sections/ps1/runtime_startup.section";
import { resolveOneOffExportDir } from "@tools-export-execution-profile/sections/shared/oneoff_export_dir.util";

function joinLines(lines: string[]): string {
  return lines.join("\n");
}

function resolveDefaults(input: {
  request: ExportExecutionProfilePs1Input;
  workspace: Record<string, unknown> | undefined;
}): ExportRuntimeDefaults {
  return resolveExportDefaults({ request: input.request, workspace: input.workspace });
}

export async function exportExecutionProfilePs1(input: ExportExecutionProfilePs1Input): Promise<ExportExecutionProfilePs1Result> {
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

  const runtimeStartupSection = buildPs1RuntimeStartupSection({
    workspaceRootAbs: input.workspaceRootAbs,
    workspace,
    runtimeContextName: manifest.runtimeContextName,
    includeRuntimeStartup: defaults.includeRuntimeStartup,
  });
  const healthcheckGateSection = buildPs1HealthcheckSection({
    workspace,
    includeHealthcheckGate: defaults.includeHealthcheckGate,
  });
  const planExecutionSection = await buildPs1PlanExecutionSection({
    workspaceRootAbs: input.workspaceRootAbs,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
    workspace,
    executionProfile: manifest.executionProfile,
    planRuns: manifest.planRuns,
  });
  const prerequisitesSections = await buildPs1PrerequisitesSections({
    workspaceRootAbs: input.workspaceRootAbs,
    ...(typeof input.projectName === "string" && input.projectName.trim().length > 0
      ? { projectName: input.projectName.trim() }
      : {}),
    workspace,
    executionProfile: manifest.executionProfile,
    planRuns: manifest.planRuns,
    planExecutionSection,
  });
  const exportPackageSections = await preparePs1ExportPackage({
    workspaceRootAbs: input.workspaceRootAbs,
    exportDirAbs,
    workspace,
    executionProfile: manifest.executionProfile,
    includeResolvedSecrets,
  });

  const scriptText = await renderEtaTemplate({
    templateFileName: "run-execution-profile.ps1.eta",
    data: {
      manifest,
      includeResolvedSecrets,
      prerequisitesSection: joinLines(prerequisitesSections.prerequisitesSection),
      preRuntimeScriptSection: joinLines(exportPackageSections.preRuntimeScriptSection),
      runtimeStartupSection: joinLines(runtimeStartupSection),
      postRuntimeScriptSection: joinLines(exportPackageSections.postRuntimeScriptSection),
      healthcheckGateSection: joinLines(healthcheckGateSection),
      postHealthcheckScriptSection: joinLines(exportPackageSections.postHealthcheckScriptSection),
      postStartupAuthSection: joinLines(prerequisitesSections.postStartupAuthSection),
      prePlanScriptSection: joinLines(exportPackageSections.prePlanScriptSection),
      planExecutionSection: joinLines(planExecutionSection),
    },
  });

  const readmeText = await renderEtaTemplate({
    templateFileName: "README.execution-profile-export.md.eta",
    data: buildReadmeTemplateModel({ manifest, defaults, includeResolvedSecrets }),
  });

  const scriptPathAbs = path.join(exportDirAbs, "run-execution-profile.ps1");
  const readmePathAbs = path.join(exportDirAbs, "README.ps1.md");
  await fs.writeFile(scriptPathAbs, scriptText, "utf8");
  await fs.writeFile(readmePathAbs, readmeText, "utf8");

  return {
    exportId: manifest.exportId,
    exportDirAbs,
    scriptPathAbs,
    readmePathAbs,
  };
}
