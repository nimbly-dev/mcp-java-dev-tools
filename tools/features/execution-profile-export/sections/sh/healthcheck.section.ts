import { collectHealthchecks } from "@tools-export-execution-profile/collectors/healthchecks.collector";
import { applyHealthcheckPolicy } from "@tools-export-execution-profile/policy/healthcheck_policy";
import {
  renderShHealthcheckCommands,
  renderShHealthcheckSection,
} from "@tools-export-execution-profile/renderers/sh.command.renderer";

export function buildShHealthcheckSection(input: {
  workspace: Record<string, unknown> | undefined;
  includeHealthcheckGate: boolean;
}): string[] {
  const allHealthchecks = collectHealthchecks(input.workspace);
  const healthchecks = applyHealthcheckPolicy(allHealthchecks);
  const healthcheckCommands = renderShHealthcheckCommands(healthchecks);
  return renderShHealthcheckSection(healthcheckCommands, input.includeHealthcheckGate);
}
