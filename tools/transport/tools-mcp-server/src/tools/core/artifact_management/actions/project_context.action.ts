import path from "node:path";
import type { ExecutionProfileEntry, ProjectArtifact } from "@tools-project-artifact-spec/models/project_artifact.model";
import { readProjectArtifact, validateProjectArtifact, writeProjectArtifact } from "@tools-project-artifact-spec/project_artifact.util";
import type { ArtifactActionContext, ArtifactActionRequest, ArtifactActionResult } from "@/tools/core/artifact_management/actions/types";
import { buildFailClosedArtifactResponse, okArtifactResponse } from "@/tools/core/artifact_management/shared/fail_closed.util";
import { listProjectNames, resolveProjectName } from "@/tools/core/artifact_management/shared/project_resolution.util";

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

  const projectName = await resolveProjectName(ctx.workspaceRootAbs, request.input.projectName);
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
