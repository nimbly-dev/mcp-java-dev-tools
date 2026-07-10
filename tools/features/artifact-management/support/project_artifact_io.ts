import { promises as fs } from "node:fs";
import path from "node:path";
import type { ProjectArtifact, ProjectArtifactValidationResult } from "@tools-project-artifact-spec/models/project_artifact.model";
import {
  validateProjectArtifact,
  validateProjectArtifactReferenceIntegrity,
} from "@tools-project-artifact-spec/project_artifact.util";

export async function readProjectArtifact(projectsFileAbs: string): Promise<ProjectArtifactValidationResult> {
  const text = (await fs.readFile(projectsFileAbs, "utf8")).replace(/^\uFEFF/, "");
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return {
      ok: false,
      reasonCode: "project_artifact_invalid",
      errors: ["projects.json is not valid JSON"],
    };
  }
  const validated = validateProjectArtifact(parsed);
  if (!validated.ok) return validated;
  return validateProjectArtifactReferenceIntegrity({
    projectsFileAbs,
    artifact: validated.artifact,
  });
}

export async function writeProjectArtifact(projectsFileAbs: string, artifact: ProjectArtifact): Promise<void> {
  await fs.mkdir(path.dirname(projectsFileAbs), { recursive: true });
  await fs.writeFile(projectsFileAbs, `${JSON.stringify(artifact, null, 2)}\n`, "utf8");
}
