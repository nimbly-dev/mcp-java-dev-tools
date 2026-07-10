import { collectRuntimeStartups } from "../../collectors/runtime_startups.collector";
import type { RuntimeStartup } from "../../models/execution_profile_export.model";
import { renderShRuntimeStartupSection } from "../../renderers/sh.command.renderer";

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
