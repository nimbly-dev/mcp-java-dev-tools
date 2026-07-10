import * as fs from "node:fs/promises";
import * as path from "node:path";

export type ProjectRootValidation =
  | {
      ok: true;
      projectRootAbs: string;
    }
  | {
      ok: false;
      status: "project_selector_required" | "project_selector_invalid";
      nextAction: string;
      reason: string;
      value?: string;
    };

export async function validateProjectRootAbs(
  projectRootAbs: string | undefined,
): Promise<ProjectRootValidation> {
  if (typeof projectRootAbs !== "string" || projectRootAbs.trim().length === 0) {
    return {
      ok: false,
      status: "project_selector_required",
      reason: "projectRootAbs is required",
      nextAction:
        "Provide projectRootAbs as an existing project directory path (absolute or relative to workspace).",
    };
  }

  const trimmed = projectRootAbs.trim();
  const resolved =
    path.isAbsolute(trimmed)
      ? trimmed
      : path.resolve(trimmed);

  let stat;
  try {
    stat = await fs.stat(resolved);
  } catch {
    return {
      ok: false,
      status: "project_selector_invalid",
      reason: "projectRootAbs does not exist",
      value: resolved,
      nextAction:
        "Provide an existing projectRootAbs path (absolute or relative to current workspace context).",
    };
  }

  if (!stat.isDirectory()) {
    return {
      ok: false,
      status: "project_selector_invalid",
      reason: "projectRootAbs must be a directory",
      value: resolved,
      nextAction: "Provide an existing project directory path for projectRootAbs.",
    };
  }

  return {
    ok: true,
    projectRootAbs: path.resolve(resolved),
  };
}
