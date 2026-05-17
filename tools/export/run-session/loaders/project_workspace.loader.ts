import path from "node:path";

import { readProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";

export async function loadProjectWorkspace(input: {
  workspaceRootAbs: string;
  projectRootAbs: string;
}): Promise<Record<string, unknown> | undefined> {
  const projectName = path.basename(input.projectRootAbs);
  const projectsFileAbs = path.join(input.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
  const parsed = await readProjectArtifact(projectsFileAbs).catch(() => undefined);
  if (!parsed || !parsed.ok) {
    return undefined;
  }
  return parsed.artifact.workspaces.find((entry) => entry.projectRoot === input.workspaceRootAbs) as
    | Record<string, unknown>
    | undefined;
}
