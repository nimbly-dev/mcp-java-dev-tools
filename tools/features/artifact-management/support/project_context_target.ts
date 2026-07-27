import { resolveProjectArtifactByRootAbs, resolveProjectName } from "../shared/project_resolution";
import { validateProjectRootAbs } from "@tools-core/project_root_validate";

export type ProjectContextTarget =
  | { ok: true; projectName: string; projectRootAbs?: string }
  | { ok: false; reasonCode: string; reason: string; reasonMeta?: Record<string, unknown> };

export async function resolveProjectContextTarget(args: {
  workspaceRootAbs: string;
  projectName?: string;
  projectRootAbs?: string;
}): Promise<ProjectContextTarget> {
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
    return resolveProjectArtifactByRootAbs({
      workspaceRootAbs: args.workspaceRootAbs,
      projectRootAbs: args.projectRootAbs!,
    });
  }
  try {
    return { ok: true, projectName: await resolveProjectName(args.workspaceRootAbs) };
  } catch (error) {
    const reasonCode = error instanceof Error ? error.message : String(error);
    return {
      ok: false,
      reasonCode,
      reason:
        reasonCode === "project_artifact_missing"
          ? "no project artifact was found in the workspace"
          : "multiple project artifacts exist in the workspace",
      reasonMeta: { failedStep: "project_resolution" },
    };
  }
}
