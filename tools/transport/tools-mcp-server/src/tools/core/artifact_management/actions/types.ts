import type { ArtifactManagementRequest } from "@/models/inputs/artifact_management";

export type ArtifactActionResult = {
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
};

export type ArtifactActionContext = {
  workspaceRootAbs: string;
};

export type ArtifactActionRequest<T extends ArtifactManagementRequest["artifactType"]> = Extract<
  ArtifactManagementRequest,
  { artifactType: T }
>;
