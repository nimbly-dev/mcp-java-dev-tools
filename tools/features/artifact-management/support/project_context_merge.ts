import path from "node:path";
import type { ProjectArtifact } from "@tools-project-artifact-spec/models/project_artifact.model";

export function mergeProjectArtifacts(
  existing: ProjectArtifact,
  incoming: ProjectArtifact,
): ProjectArtifact {
  const merged = existing.workspaces.map((workspace) => ({ ...workspace }));
  for (const incomingWorkspace of incoming.workspaces) {
    const existingIndex = merged.findIndex(
      (workspace) =>
        path.resolve(workspace.projectRoot) === path.resolve(incomingWorkspace.projectRoot),
    );
    if (existingIndex < 0) merged.push(incomingWorkspace);
    else {
      const current = merged[existingIndex]!;
      merged[existingIndex] = {
        ...current,
        ...incomingWorkspace,
        ...(current.variables || incomingWorkspace.variables
          ? {
              variables: {
                ...current.variables,
                ...incomingWorkspace.variables,
                ...(current.variables?.contextBindings ||
                incomingWorkspace.variables?.contextBindings
                  ? {
                      contextBindings: {
                        ...current.variables?.contextBindings,
                        ...incomingWorkspace.variables?.contextBindings,
                      },
                    }
                  : {}),
              },
            }
          : {}),
        ...(current.defaults || incomingWorkspace.defaults
          ? {
              defaults: {
                ...current.defaults,
                ...incomingWorkspace.defaults,
                orchestrator: {
                  ...current.defaults?.orchestrator,
                  ...incomingWorkspace.defaults?.orchestrator,
                },
              },
            }
          : {}),
        ...(current.sessionExport || incomingWorkspace.sessionExport
          ? { sessionExport: { ...current.sessionExport, ...incomingWorkspace.sessionExport } }
          : {}),
      };
    }
  }
  return { workspaces: merged };
}
