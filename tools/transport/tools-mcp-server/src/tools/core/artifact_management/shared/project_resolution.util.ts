import { promises as fs } from "node:fs";
import path from "node:path";

export async function listProjectNames(workspaceRootAbs: string): Promise<string[]> {
  const root = path.join(workspaceRootAbs, ".mcpjvm");
  const entries = await fs.readdir(root, { withFileTypes: true }).catch(() => []);
  const out: string[] = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const candidate = path.join(root, entry.name, "projects.json");
    const stat = await fs.stat(candidate).catch(() => null);
    if (stat?.isFile()) out.push(entry.name);
  }
  return out.sort((a, b) => a.localeCompare(b));
}

export async function resolveProjectName(workspaceRootAbs: string, projectName?: string): Promise<string> {
  if (typeof projectName === "string" && projectName.trim().length > 0) {
    return projectName.trim();
  }
  const names = await listProjectNames(workspaceRootAbs);
  if (names.length === 1) return names[0] as string;
  if (names.length === 0) throw new Error("project_artifact_missing");
  throw new Error("project_artifact_ambiguous");
}
