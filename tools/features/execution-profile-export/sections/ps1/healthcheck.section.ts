import { collectHealthchecks } from "@tools-export-execution-profile/collectors/healthchecks.collector";
import { applyHealthcheckPolicy } from "@tools-export-execution-profile/policy/healthcheck_policy";
import {
  renderPs1HealthcheckCommands,
  renderPs1HealthcheckSection,
} from "@tools-export-execution-profile/renderers/ps1.command.renderer";

export function buildPs1HealthcheckSection(input: {
  workspace: Record<string, unknown> | undefined;
  includeHealthcheckGate: boolean;
}): string[] {
  const checks = applyHealthcheckPolicy(collectHealthchecks(input.workspace));
  const commands = renderPs1HealthcheckCommands(checks);
  return renderPs1HealthcheckSection(commands, input.includeHealthcheckGate);
}
