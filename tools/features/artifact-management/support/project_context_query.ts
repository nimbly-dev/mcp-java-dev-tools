import type {
  ExecutionProfileEntry,
  ProjectArtifact,
} from "@tools-project-artifact-spec/models/project_artifact.model";

function asStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter(
    (entry): entry is string => typeof entry === "string" && entry.trim().length > 0,
  );
}

export function pickProjectContextQuery(args: {
  artifact: ProjectArtifact;
  query?: { select?: string[] | undefined; executionProfile?: string | undefined };
}): Record<string, unknown> {
  const selectors = asStringArray(args.query?.select);
  const workspace = args.artifact.workspaces[0];
  if (selectors.length === 0) {
    const profiles = Array.isArray(workspace?.executionProfiles) ? workspace.executionProfiles : [];
    const runtimeContexts = Array.isArray(workspace?.runtimeContexts)
      ? workspace.runtimeContexts
      : [];
    return {
      summary: {
        workspaceCount: args.artifact.workspaces.length,
        executionProfileCount: profiles.length,
        runtimeContextCount: runtimeContexts.length,
        executionProfileNames: profiles
          .map((entry) => entry.executionProfile)
          .sort((a, b) => a.localeCompare(b)),
        runtimeContextNames: runtimeContexts
          .map((entry) => entry.name)
          .sort((a, b) => a.localeCompare(b)),
      },
    };
  }
  const profileName = args.query?.executionProfile;
  const result: Record<string, unknown> = {};
  for (const selector of selectors) {
    if (selector === "artifact") result.artifact = args.artifact;
    else if (selector === "summary") {
      result.summary = {
        workspaceCount: args.artifact.workspaces.length,
        executionProfileCount: Array.isArray(workspace?.executionProfiles)
          ? workspace.executionProfiles.length
          : 0,
        runtimeContextCount: Array.isArray(workspace?.runtimeContexts)
          ? workspace.runtimeContexts.length
          : 0,
      };
    } else if (selector === "workspaces") result.workspaces = args.artifact.workspaces;
    else if (selector === "executionProfiles") {
      const profiles = Array.isArray(workspace?.executionProfiles)
        ? workspace.executionProfiles
        : [];
      result.executionProfiles = profileName
        ? profiles.filter((entry: ExecutionProfileEntry) => entry.executionProfile === profileName)
        : profiles;
    } else if (selector === "runtimeContexts") {
      result.runtimeContexts = Array.isArray(workspace?.runtimeContexts)
        ? workspace.runtimeContexts
        : [];
    } else if (selector === "scripts")
      result.scripts = Array.isArray(workspace?.scripts) ? workspace.scripts : [];
    else if (selector === "runPrerequisites") {
      result.runPrerequisites = Array.isArray(workspace?.runPrerequisites)
        ? workspace.runPrerequisites
        : [];
    }
  }
  return Object.keys(result).length > 0
    ? result
    : { summary: { workspaceCount: args.artifact.workspaces.length } };
}
