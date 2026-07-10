import { promises as fs } from "node:fs";
import path from "node:path";
import { readProjectArtifact } from "../support/project_artifact_io";
import { validateProjectRootAbs } from "@tools-core/project_root_validate";

type ProjectArtifactRootMatch =
  | {
      ok: true;
      projectName: string;
      projectRootAbs: string;
    }
  | {
      ok: false;
      reasonCode:
        | "project_selector_required"
        | "project_selector_invalid"
        | "project_artifact_missing"
        | "project_artifact_ambiguous";
      reason: string;
      reasonMeta?: Record<string, unknown>;
    };

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

export async function resolveProjectArtifactByRootAbs(args: {
  workspaceRootAbs: string;
  projectRootAbs: string;
}): Promise<ProjectArtifactRootMatch> {
  const validatedRoot = await validateProjectRootAbs(args.projectRootAbs);
  if (!validatedRoot.ok) {
    return {
      ok: false,
      reasonCode: validatedRoot.status,
      reason: validatedRoot.reason,
      reasonMeta: {
        failedStep: "project_root_validation",
        ...(validatedRoot.value ? { projectRootAbs: validatedRoot.value } : {}),
      },
    };
  }

  const requestedRoot = path.resolve(validatedRoot.projectRootAbs);
  const projectNames = await listProjectNames(args.workspaceRootAbs);
  const matches: Array<{ projectName: string; projectRootAbs: string }> = [];

  for (const projectName of projectNames) {
    const projectsFileAbs = path.join(args.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");
    const parsed = await readProjectArtifact(projectsFileAbs).catch(() => null);
    if (!parsed?.ok) {
      continue;
    }
    for (const workspace of parsed.artifact.workspaces) {
      if (path.resolve(workspace.projectRoot) === requestedRoot) {
        matches.push({ projectName, projectRootAbs: requestedRoot });
        break;
      }
    }
  }

  if (matches.length === 1) {
    const match = matches[0]!;
    return {
      ok: true,
      projectName: match.projectName,
      projectRootAbs: match.projectRootAbs,
    };
  }
  if (matches.length === 0) {
    return {
      ok: false,
      reasonCode: "project_artifact_missing",
      reason: "projectRootAbs does not match any project artifact workspace",
      reasonMeta: {
        failedStep: "project_resolution",
        projectRootAbs: requestedRoot,
      },
    };
  }
  return {
    ok: false,
    reasonCode: "project_artifact_ambiguous",
    reason: "projectRootAbs matches multiple project artifacts",
    reasonMeta: {
      failedStep: "project_resolution",
      projectRootAbs: requestedRoot,
      projectNames: matches.map((entry) => entry.projectName),
    },
  };
}
