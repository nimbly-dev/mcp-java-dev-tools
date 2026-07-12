import path from "node:path";

export function cutoverMarkerPath(databasePathAbs: string): string {
  return path.join(path.dirname(databasePathAbs), "state-store.cutover.json");
}

export function cutoverSentinelPath(workspaceRootAbs: string, projectName: string): string {
  return path.resolve(workspaceRootAbs, ".mcpjvm", "state-store-cutovers", `${projectName}.json`);
}
