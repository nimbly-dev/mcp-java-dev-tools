import { collectRuntimeStartups } from "@tools-export-execution-profile/collectors/runtime_startups.collector";
import type { RuntimeStartup } from "@tools-export-execution-profile/models/execution_profile_export.model";
import { renderShRuntimeStartupSection } from "@tools-export-execution-profile/renderers/sh.command.renderer";

export function buildShRuntimeStartupSection(input: {
  workspaceRootAbs: string;
  workspace: Record<string, unknown> | undefined;
  runtimeContextName: string | undefined;
  includeRuntimeStartup: boolean;
}): string[] {
  const runtimeStartups: RuntimeStartup[] = collectRuntimeStartups({
    workspace: input.workspace,
    runtimeContextName: input.runtimeContextName,
    workspaceRootAbs: input.workspaceRootAbs,
  });
  return renderShRuntimeStartupSection(runtimeStartups, input.includeRuntimeStartup);
}
