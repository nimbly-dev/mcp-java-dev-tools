import { collectHealthchecks } from "../../collectors/healthchecks.collector";
import { applyHealthcheckPolicy } from "../../policy/healthcheck_policy";
import {
  renderShHealthcheckCommands,
  renderShHealthcheckSection,
} from "../../renderers/sh.command.renderer";

export function buildShHealthcheckSection(input: {
  workspace: Record<string, unknown> | undefined;
  includeHealthcheckGate: boolean;
}): string[] {
  const allHealthchecks = collectHealthchecks(input.workspace);
  const healthchecks = applyHealthcheckPolicy(allHealthchecks);
  const healthcheckCommands = renderShHealthcheckCommands(healthchecks);
  return renderShHealthcheckSection(healthcheckCommands, input.includeHealthcheckGate);
}
