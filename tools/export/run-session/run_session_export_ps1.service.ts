import { promises as fs } from "node:fs";
import path from "node:path";

import { collectHealthchecks } from "@tools-export-run-session/collectors/healthchecks.collector";
import { collectRuntimeStartups } from "@tools-export-run-session/collectors/runtime_startups.collector";
import { loadProjectWorkspace } from "@tools-export-run-session/loaders/project_workspace.loader";
import { loadRunSessionManifest } from "@tools-export-run-session/loaders/session_manifest.loader";
import type {
  ExportRunSessionPs1Input,
  ExportRunSessionPs1Result,
  ExportRuntimeDefaults,
  Healthcheck,
  RuntimeStartup,
} from "@tools-export-run-session/models/run_session_export.model";
import { applyHealthcheckPolicy } from "@tools-export-run-session/policy/healthcheck_policy";
import { resolveExportDefaults } from "@tools-export-run-session/policy/export_defaults.policy";
import { renderEtaTemplate } from "@tools-export-run-session/renderers/eta.renderer";
import { renderPs1PlanExecutionSection } from "@tools-export-run-session/renderers/plan.command.renderer";
import {
  renderPs1HealthcheckCommands,
  renderPs1HealthcheckSection,
  renderPs1RuntimeStartupSection,
} from "@tools-export-run-session/renderers/ps1.command.renderer";
import { buildReadmeTemplateModel } from "@tools-export-run-session/renderers/readme.renderer";

function joinLines(lines: string[]): string {
  return lines.join("\n");
}

function normalizeRuntimeDefaults(input: {
  request: ExportRunSessionPs1Input;
  workspace: Record<string, unknown> | undefined;
}): ExportRuntimeDefaults {
  return resolveExportDefaults({ request: input.request, workspace: input.workspace });
}

function resolveRuntimeData(input: {
  workspace: Record<string, unknown> | undefined;
  runtimeContextName: string | undefined;
  defaults: ExportRuntimeDefaults;
}): { runtimeStartups: RuntimeStartup[]; healthchecks: Healthcheck[] } {
  const runtimeStartups = collectRuntimeStartups({
    workspace: input.workspace,
    runtimeContextName: input.runtimeContextName,
  });
  const allHealthchecks = collectHealthchecks(input.workspace);
  const healthchecks = applyHealthcheckPolicy(allHealthchecks);
  return { runtimeStartups, healthchecks };
}

export async function exportRunSessionPs1(
  input: ExportRunSessionPs1Input,
): Promise<ExportRunSessionPs1Result> {
  const { manifest, manifestPathAbs, sessionDirAbs, projectRootAbs } = await loadRunSessionManifest({
    workspaceRootAbs: input.workspaceRootAbs,
    sessionId: input.sessionId,
  });

  const workspace = await loadProjectWorkspace({
    workspaceRootAbs: input.workspaceRootAbs,
    projectRootAbs,
  });
  const defaults = normalizeRuntimeDefaults({ request: input, workspace });

  const { runtimeStartups, healthchecks } = resolveRuntimeData({
    workspace,
    runtimeContextName: manifest.runtimeContextName,
    defaults,
  });

  const runtimeStartupSection = renderPs1RuntimeStartupSection(
    runtimeStartups,
    defaults.includeRuntimeStartup,
  );
  const healthcheckCommands = renderPs1HealthcheckCommands(healthchecks);
  const healthcheckGateSection = renderPs1HealthcheckSection(
    healthcheckCommands,
    defaults.includeHealthcheckGate,
  );
  const planExecutionSection = renderPs1PlanExecutionSection(manifest.planRuns);

  const includeResolvedSecrets = input.includeResolvedSecrets === true;

  const scriptText = await renderEtaTemplate({
    templateFileName: "replay-session.ps1.eta",
    data: {
      manifest,
      includeResolvedSecrets,
      runtimeStartupSection: joinLines(runtimeStartupSection),
      healthcheckGateSection: joinLines(healthcheckGateSection),
      planExecutionSection: joinLines(planExecutionSection),
    },
  });

  const readmeText = await renderEtaTemplate({
    templateFileName: "README.session-export.md.eta",
    data: buildReadmeTemplateModel({
      manifest,
      defaults,
      includeResolvedSecrets,
    }),
  });

  const scriptPathAbs = path.join(sessionDirAbs, "replay-session.ps1");
  const readmePathAbs = path.join(sessionDirAbs, "README.ps1.md");

  await fs.writeFile(scriptPathAbs, scriptText, "utf8");
  await fs.writeFile(readmePathAbs, readmeText, "utf8");

  return {
    sessionId: manifest.sessionId,
    sessionDirAbs,
    manifestPathAbs,
    scriptPathAbs,
    readmePathAbs,
  };
}
