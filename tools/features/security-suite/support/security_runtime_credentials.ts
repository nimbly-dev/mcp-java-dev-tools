import fs from "node:fs/promises";
import path from "node:path";

import { readProjectArtifact } from "@tools-feature-artifact-management";

function parseDotEnv(input: string): Record<string, string> {
  const values: Record<string, string> = {};
  for (const rawLine of input.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    const line = rawLine.trim();
    if (line.length === 0 || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const key = line.slice(0, separator).trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/u.test(key)) continue;
    let value = line.slice(separator + 1);
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
      value = value.slice(1, -1);
    }
    values[key] = value;
  }
  return values;
}

async function readWorkspaceEnvFile(args: {
  workspaceRootAbs: string;
  envFile: string | undefined;
}): Promise<Record<string, string>> {
  if (!args.envFile) return {};
  const envFileAbs = path.isAbsolute(args.envFile)
    ? args.envFile
    : path.resolve(args.workspaceRootAbs, args.envFile);
  try {
    return parseDotEnv(await fs.readFile(envFileAbs, "utf8"));
  } catch {
    return {};
  }
}

export async function resolveSecurityRuntimeCredentialContext(args: {
  workspaceRootAbs: string;
  projectName: string;
  executionProfile: string;
}): Promise<Record<string, string>> {
  const projectsFileAbs = path.join(
    args.workspaceRootAbs,
    ".mcpjvm",
    args.projectName,
    "projects.json",
  );
  const artifact = await readProjectArtifact(projectsFileAbs).catch(() => undefined);
  if (!artifact?.ok) return {};
  const workspace = artifact.artifact.workspaces.find(
    (entry) => entry.projectRoot === args.workspaceRootAbs,
  );
  if (!workspace) return {};
  const profile = (workspace.executionProfiles ?? []).find(
    (entry) => entry.executionProfile === args.executionProfile && entry.suiteType === "security",
  );
  if (!profile) return {};
  const environment = {
    ...process.env,
    ...(await readWorkspaceEnvFile({
      workspaceRootAbs: args.workspaceRootAbs,
      envFile: workspace.envFile,
    })),
  };
  const credentialContext: Record<string, string> = {};
  const contextBindings = workspace.variables?.contextBindings ?? {};
  for (const [contextKey, envKey] of Object.entries(contextBindings)) {
    const value = environment[envKey];
    if (value?.trim()) credentialContext[contextKey] = value;
  }
  return credentialContext;
}
