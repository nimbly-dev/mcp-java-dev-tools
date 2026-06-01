import { promises as fs } from "node:fs";
import path from "node:path";

async function dirNames(abs: string): Promise<string[]> {
  try {
    const entries = await fs.readdir(abs, { withFileTypes: true });
    return entries.filter((entry) => entry.isDirectory()).map((entry) => entry.name);
  } catch {
    return [];
  }
}

async function fileExists(abs: string): Promise<boolean> {
  try {
    const st = await fs.stat(abs);
    return st.isFile();
  } catch {
    return false;
  }
}

export async function resolveRegressionPlansRootAbs(
  workspaceRootAbs: string,
  projectName?: string,
): Promise<string> {
  const mcpjvmRoot = path.join(workspaceRootAbs, ".mcpjvm");
  if (typeof projectName === "string" && projectName.trim().length > 0) {
    const selected = projectName.trim();
    const projectsJsonAbs = path.join(mcpjvmRoot, selected, "projects.json");
    if (!(await fileExists(projectsJsonAbs))) {
      throw new Error("project_artifact_missing");
    }
    return path.join(mcpjvmRoot, selected, "plans", "regression");
  }
  const projectDirs = await dirNames(mcpjvmRoot);
  const withProjectsJson: string[] = [];
  for (const dir of projectDirs) {
    const projectsJsonAbs = path.join(mcpjvmRoot, dir, "projects.json");
    if (await fileExists(projectsJsonAbs)) withProjectsJson.push(dir);
  }
  if (withProjectsJson.length === 1) {
    const projectName = withProjectsJson[0];
    if (projectName) {
      return path.join(mcpjvmRoot, projectName, "plans", "regression");
    }
  }
  if (withProjectsJson.length === 0) {
    throw new Error("project_artifact_missing");
  }
  throw new Error("project_artifact_ambiguous");
}
