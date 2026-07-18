import * as fs from "node:fs";
import * as path from "node:path";
import { fileURLToPath } from "node:url";

export type WorkspaceContext = {
  workspaceRootAbs: string | undefined;
  source: "roots" | "arg" | "env" | "session" | "cwd" | "probe-config" | "missing" | "ambiguous";
  reasonCode?: "workspace_context_missing" | "workspace_context_ambiguous";
};

export type WorkspaceRoot = { uri: string; name?: string | undefined };

function fileUriToPath(uri: string): string | undefined {
  try {
    return fileURLToPath(uri);
  } catch {
    return undefined;
  }
}

export function normalizeWorkspaceRoot(rootAbs: string): string {
  const resolved = path.resolve(rootAbs);
  return path.basename(resolved).toLowerCase() === ".mcpjvm" ? path.dirname(resolved) : resolved;
}

export function resolveProbeConfigFileForWorkspace(
  workspaceRootAbs: string,
  explicitOverride: string | undefined,
): string | undefined {
  if (!explicitOverride) {
    return path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  }

  const normalizedOverride = explicitOverride.replaceAll("\\", "/");
  if (
    normalizedOverride === ".mcpjvm/probe-config.json" ||
    normalizedOverride === "/.mcpjvm/probe-config.json"
  ) {
    return path.join(workspaceRootAbs, ".mcpjvm", "probe-config.json");
  }
  return path.resolve(explicitOverride);
}

export function resolveWorkspaceFromRoots(roots: WorkspaceRoot[]): WorkspaceContext {
  const candidates = roots
    .map((root) => fileUriToPath(root.uri))
    .filter((root): root is string => typeof root === "string")
    .map(normalizeWorkspaceRoot)
    .filter((root, index, all) => all.indexOf(root) === index);
  const canonical = candidates.filter((root) =>
    fs.existsSync(path.join(root, ".mcpjvm", "probe-config.json")),
  );

  if (canonical.length > 1) {
    return {
      workspaceRootAbs: undefined,
      source: "ambiguous",
      reasonCode: "workspace_context_ambiguous",
    };
  }
  const workspaceRootAbs = canonical[0] ?? candidates[0];
  return workspaceRootAbs
    ? { workspaceRootAbs, source: "roots" }
    : { workspaceRootAbs: undefined, source: "missing", reasonCode: "workspace_context_missing" };
}
