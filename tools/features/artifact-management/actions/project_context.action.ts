import path from "node:path";
import { promises as fs } from "node:fs";
import type { ExecutionProfileEntry, ProjectArtifact } from "@tools-project-artifact-spec/models/project_artifact.model";
import {
  readProjectArtifact,
  validateProjectArtifact,
  validateProjectArtifactReferenceIntegrity,
  writeProjectArtifact,
} from "@tools-project-artifact-spec/project_artifact.util";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "./types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "../shared/fail_closed";
import {
  listProjectNames,
  resolveProjectArtifactByRootAbs,
  resolveProjectName,
} from "../shared/project_resolution";
import { validateProjectRootAbs } from "@/utils/project_root_validate.util";

async function dirExists(abs: string): Promise<boolean> {
  try {
    const stat = await fs.stat(abs);
    return stat.isDirectory();
  } catch {
    return false;
  }
}

async function fileExists(abs: string): Promise<boolean> {
  try {
    const stat = await fs.stat(abs);
    return stat.isFile();
  } catch {
    return false;
  }
}

async function inspectProjectRoot(projectRootAbs: string): Promise<{
  buildMarkers: string[];
  hasBuildMarker: boolean;
  javaSourceRoots: string[];
  hasJavaSourceRoot: boolean;
}> {
  const buildMarkers: string[] = [];
  if (await fileExists(path.join(projectRootAbs, "pom.xml"))) buildMarkers.push("pom.xml");
  if (await fileExists(path.join(projectRootAbs, "build.gradle"))) buildMarkers.push("build.gradle");
  if (await fileExists(path.join(projectRootAbs, "build.gradle.kts"))) buildMarkers.push("build.gradle.kts");

  const javaSourceRoots: string[] = [];
  const sourceRootAbs = path.join(projectRootAbs, "src", "main", "java");
  if (await dirExists(sourceRootAbs)) {
    javaSourceRoots.push(sourceRootAbs);
  }

  return {
    buildMarkers,
    hasBuildMarker: buildMarkers.length > 0,
    javaSourceRoots,
    hasJavaSourceRoot: javaSourceRoots.length > 0,
  };
}

async function resolveProjectContextTarget(args: {
  workspaceRootAbs: string;
  projectName?: string;
  projectRootAbs?: string;
}): Promise<
  | {
      ok: true;
      projectName: string;
      projectRootAbs?: string;
    }
  | {
      ok: false;
      reasonCode: string;
      reason: string;
      reasonMeta?: Record<string, unknown>;
    }
> {
  const hasProjectName = typeof args.projectName === "string" && args.projectName.trim().length > 0;
  const hasProjectRootAbs =
    typeof args.projectRootAbs === "string" && args.projectRootAbs.trim().length > 0;

  if (hasProjectName) {
    let normalizedRoot: string | undefined;
    if (hasProjectRootAbs) {
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
      normalizedRoot = validatedRoot.projectRootAbs;
    }
    return {
      ok: true,
      projectName: args.projectName!.trim(),
      ...(normalizedRoot ? { projectRootAbs: normalizedRoot } : {}),
    };
  }

  if (hasProjectRootAbs) {
    return await resolveProjectArtifactByRootAbs({
      workspaceRootAbs: args.workspaceRootAbs,
      projectRootAbs: args.projectRootAbs!,
    });
  }

  try {
    return {
      ok: true,
      projectName: await resolveProjectName(args.workspaceRootAbs),
    };
  } catch (error) {
    const reasonCode = error instanceof Error ? error.message : String(error);
    return {
      ok: false,
      reasonCode,
      reason:
        reasonCode === "project_artifact_missing"
          ? "no project artifact was found in the workspace"
          : "multiple project artifacts exist in the workspace",
      reasonMeta: {
        failedStep: "project_resolution",
      },
    };
  }
}

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((entry): entry is string => typeof entry === "string" && entry.trim().length > 0);
}

function pickProjectContextQuery(args: {
  artifact: ProjectArtifact;
  query?: { select?: string[] | undefined; executionProfile?: string | undefined };
}): Record<string, unknown> {
  const selectors = asStringArray(args.query?.select);
  const workspace = args.artifact.workspaces[0];
  if (selectors.length === 0) {
    const profiles = Array.isArray(workspace?.executionProfiles) ? workspace.executionProfiles : [];
    const runtimeContexts = Array.isArray(workspace?.runtimeContexts) ? workspace.runtimeContexts : [];
    return {
      summary: {
        workspaceCount: args.artifact.workspaces.length,
        executionProfileCount: profiles.length,
        runtimeContextCount: runtimeContexts.length,
        executionProfileNames: profiles.map((entry) => entry.executionProfile).sort((a, b) => a.localeCompare(b)),
        runtimeContextNames: runtimeContexts.map((entry) => entry.name).sort((a, b) => a.localeCompare(b)),
      },
    };
  }
  const profileName = args.query?.executionProfile;
  const result: Record<string, unknown> = {};
  for (const selector of selectors) {
    if (selector === "summary") {
      result.summary = {
        workspaceCount: args.artifact.workspaces.length,
        executionProfileCount: Array.isArray(workspace?.executionProfiles) ? workspace.executionProfiles.length : 0,
        runtimeContextCount: Array.isArray(workspace?.runtimeContexts) ? workspace.runtimeContexts.length : 0,
      };
    } else if (selector === "workspaces") {
      result.workspaces = args.artifact.workspaces;
    } else if (selector === "executionProfiles") {
      const profiles = Array.isArray(workspace?.executionProfiles) ? workspace.executionProfiles : [];
      result.executionProfiles = profileName
        ? profiles.filter((entry: ExecutionProfileEntry) => entry.executionProfile === profileName)
        : profiles;
    } else if (selector === "runtimeContexts") {
      result.runtimeContexts = Array.isArray(workspace?.runtimeContexts) ? workspace.runtimeContexts : [];
    } else if (selector === "scripts") {
      result.scripts = Array.isArray(workspace?.scripts) ? workspace.scripts : [];
    } else if (selector === "runPrerequisites") {
      result.runPrerequisites = Array.isArray(workspace?.runPrerequisites) ? workspace.runPrerequisites : [];
    }
  }
  if (Object.keys(result).length === 0) {
    return {
      summary: {
        workspaceCount: args.artifact.workspaces.length,
      },
    };
  }
  return result;
}

export async function handleProjectContextArtifact(
  ctx: ArtifactActionContext,
  request: ArtifactActionRequest<"project_context">,
): Promise<ArtifactActionResult> {
  if (request.action === "list") {
    const projectNames = await listProjectNames(ctx.workspaceRootAbs);
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectNames,
    });
  }

  const projectTargetArgs: Parameters<typeof resolveProjectContextTarget>[0] = {
    workspaceRootAbs: ctx.workspaceRootAbs,
  };
  if (typeof request.input.projectName === "string") {
    projectTargetArgs.projectName = request.input.projectName;
  }
  if (typeof request.input.projectRootAbs === "string") {
    projectTargetArgs.projectRootAbs = request.input.projectRootAbs;
  }
  const projectTarget = await resolveProjectContextTarget(projectTargetArgs);
  if (!projectTarget.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: projectTarget.reasonCode,
      reason: projectTarget.reason,
      ...(projectTarget.reasonMeta ? { reasonMeta: projectTarget.reasonMeta } : {}),
    });
  }

  const projectName = projectTarget.projectName;
  const projectsFileAbs = path.join(ctx.workspaceRootAbs, ".mcpjvm", projectName, "projects.json");

  if (request.action === "validate") {
    const validated = await readProjectArtifact(projectsFileAbs);
    if (!validated.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: validated.reasonCode,
        reason: validated.errors[0] ?? "project artifact invalid",
        reasonMeta: { errors: validated.errors, projectName },
      });
    }
    let projectRootAbs = projectTarget.projectRootAbs;
    if (!projectRootAbs) {
      const workspaceRoots = validated.artifact.workspaces.map((entry) => path.resolve(entry.projectRoot));
      if (workspaceRoots.length === 1) {
        projectRootAbs = workspaceRoots[0];
      }
    }
    if (projectRootAbs) {
      const matchedWorkspace = validated.artifact.workspaces.find(
        (entry) => path.resolve(entry.projectRoot) === projectRootAbs,
      );
      if (!matchedWorkspace) {
        return buildFailClosedArtifactResponse({
          reasonCode: "project_scope_mismatch",
          reason: "projectName and projectRootAbs do not resolve to the same project scope",
          reasonMeta: {
            failedStep: "project_scope_validation",
            projectName,
            projectRootAbs,
          },
        });
      }
      const rootInspection = await inspectProjectRoot(projectRootAbs);
      return okArtifactResponse({
        resultType: "artifact",
        status: "ok",
        artifactType: request.artifactType,
        action: request.action,
        projectName,
        projectRootAbs,
        workspaceCount: validated.artifact.workspaces.length,
        ...rootInspection,
      });
    }
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      workspaceCount: validated.artifact.workspaces.length,
    });
  }

  if (request.action === "read") {
    const validated = await readProjectArtifact(projectsFileAbs);
    if (!validated.ok) {
      return buildFailClosedArtifactResponse({
        reasonCode: validated.reasonCode,
        reason: validated.errors[0] ?? "project artifact invalid",
        reasonMeta: { errors: validated.errors, projectName },
      });
    }
    const queryArgs: Parameters<typeof pickProjectContextQuery>[0] = {
      artifact: validated.artifact,
    };
    if (request.input.query) queryArgs.query = request.input.query;
    const queryResult = pickProjectContextQuery(queryArgs);
    return okArtifactResponse({
      resultType: "artifact",
      status: "ok",
      artifactType: request.artifactType,
      action: request.action,
      projectName,
      ...queryResult,
    });
  }

  if (!request.input.payload) {
    return buildFailClosedArtifactResponse({
      reasonCode: "artifact_payload_required",
      reason: "payload is required for upsert",
      reasonMeta: { artifactType: request.artifactType, action: request.action, projectName },
    });
  }
  const checked = validateProjectArtifact(request.input.payload);
  if (!checked.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: checked.reasonCode,
      reason: checked.errors[0] ?? "project artifact invalid",
      reasonMeta: { errors: checked.errors, projectName },
    });
  }
  const refsChecked = await validateProjectArtifactReferenceIntegrity({
    projectsFileAbs,
    artifact: checked.artifact,
  });
  if (!refsChecked.ok) {
    return buildFailClosedArtifactResponse({
      reasonCode: refsChecked.reasonCode,
      reason: refsChecked.errors[0] ?? "project artifact invalid",
      reasonMeta: { errors: refsChecked.errors, projectName },
    });
  }
  await writeProjectArtifact(projectsFileAbs, checked.artifact);
  return okArtifactResponse({
    resultType: "artifact",
    status: "ok",
    artifactType: request.artifactType,
    action: request.action,
    projectName,
    path: projectsFileAbs,
  });
}
