import { collectHealthchecks } from "../../collectors/healthchecks.collector";
import { applyHealthcheckPolicy } from "../../policy/healthcheck_policy";
import {
  renderPs1HealthcheckCommands,
  renderPs1HealthcheckSection,
} from "../../renderers/ps1.command.renderer";

export function buildPs1HealthcheckSection(input: {
  workspace: Record<string, unknown> | undefined;
  includeHealthcheckGate: boolean;
}): string[] {
  const checks = applyHealthcheckPolicy(collectHealthchecks(input.workspace));
  const commands = renderPs1HealthcheckCommands(checks);
  return renderPs1HealthcheckSection(commands, input.includeHealthcheckGate);
}
