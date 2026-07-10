import type { ArtifactManagementRequest } from "@tools-contracts/artifact-management";
import type { ProbeRegistrySummary } from "@/config/probe-registry";

export type ArtifactActionResult = {
  content: Array<{ type: "text"; text: string }>;
  structuredContent: Record<string, unknown>;
};

export type ArtifactActionContext = {
  workspaceRootAbs: string;
  getProbeRegistrySummary?: () => ProbeRegistrySummary | undefined;
  reloadProbeRegistry?: () => ProbeRegistrySummary | undefined;
};

export type ArtifactActionRequest<T extends ArtifactManagementRequest["artifactType"]> = Extract<
  ArtifactManagementRequest,
  { artifactType: T }
>;
