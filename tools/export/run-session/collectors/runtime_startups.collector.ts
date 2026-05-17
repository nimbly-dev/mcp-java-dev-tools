import { asString, isRecord } from "@tools-export-run-session/common";
import type { RuntimeStartup } from "@tools-export-run-session/models/run_session_export.model";

export function collectRuntimeStartups(input: {
  workspace: Record<string, unknown> | undefined;
  runtimeContextName: string | undefined;
}): RuntimeStartup[] {
  if (!input.workspace || !input.runtimeContextName) {
    return [];
  }
  const runtimeContexts = Array.isArray(input.workspace.runtimeContexts) ? input.workspace.runtimeContexts : [];
  const runtimeContext = runtimeContexts.find(
    (entry) => isRecord(entry) && asString(entry.name) === input.runtimeContextName,
  );
  if (!isRecord(runtimeContext) || !Array.isArray(runtimeContext.startups)) {
    return [];
  }

  const startups: RuntimeStartup[] = [];
  for (const [idx, startup] of runtimeContext.startups.entries()) {
    if (!isRecord(startup)) {
      continue;
    }
    const command = asString(startup.command);
    if (!command) {
      continue;
    }
    const args = Array.isArray(startup.args)
      ? startup.args.filter((arg): arg is string => typeof arg === "string" && arg.trim().length > 0)
      : [];
    const title = asString(startup.name) ?? `startup-${idx + 1}`;
    startups.push({
      id: `R${String(startups.length + 1).padStart(2, "0")}`,
      title,
      command: [command, ...args].join(" "),
    });
  }
  return startups;
}
